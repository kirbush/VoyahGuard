"""Upload-only VoyahGuard (Сторож) report receiver, a copy of the FM receiver with its own
prefix, report names, storage and keys. Run behind HTTPS nginx, never expose its port."""
import argparse
import collections
import hashlib
import hmac
import io
import json
import os
import pathlib
import re
import struct
import threading
import time
import uuid
import zipfile
import zlib

APP = 'voyah-guard'
PREFIX = '/voyah-guard/v1/'
MEMBERS = {'environment.txt', 'diagnostics.txt', 'events.log', 'events.previous.log'}
REPORT_NAME = re.compile(r'Voyah-Guard-report-[0-9]{8}-[0-9]{6}(?:-[0-9]+)?\.zip\Z')
VERSION = re.compile(r'[0-9A-Za-z._-]{1,32}\Z')

class Reject(Exception):
    def __init__(self, status, code): self.status, self.code = status, code

class Receiver:
    def __init__(self, config_path, data_path):
        config = json.loads(pathlib.Path(config_path).read_text('utf8'))
        self.clients = config['clients']
        if not self.clients: raise ValueError('No authorized clients configured')
        for name, client in self.clients.items():
            if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', name): raise ValueError('Invalid client id')
            if not re.fullmatch(r'[0-9a-f]{64}', client['token_sha256']): raise ValueError('Invalid token hash')
        self.max_bytes = min(int(config.get('max_bytes', 16*1024*1024)), 16*1024*1024)
        self.quota_bytes = int(config.get('quota_bytes', 512*1024*1024))
        self.hourly_limit = int(config.get('hourly_limit', 30))
        self.retention_days = int(config.get('retention_days', 30))
        if min(self.max_bytes,self.quota_bytes,self.hourly_limit,self.retention_days) <= 0:
            raise ValueError('Invalid storage limits')
        self.data = pathlib.Path(data_path)
        self.data.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.accepted = collections.defaultdict(collections.deque)
        self.cleanup()

    def cleanup(self):
        with self.lock:
            cutoff = time.time()-self.retention_days*86400
            for directory in self.data.iterdir():
                if directory.is_symlink() or not directory.is_dir() or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', directory.name): continue
                for file in directory.iterdir():
                    if file.is_symlink() or not file.is_file(): continue
                    if re.fullmatch(r'[0-9a-f]{64}\.zip', file.name) and file.stat().st_mtime < cutoff:
                        file.unlink()
                        file.with_suffix('.json').unlink(missing_ok=True)
                    elif re.fullmatch(r'\.[0-9a-f]{32}\.partial', file.name) and file.stat().st_mtime < time.time()-86400:
                        file.unlink()

    def client(self, authorization):
        if not authorization.startswith('Bearer ') or len(authorization)>256: raise Reject(401,'unauthorized')
        digest = hashlib.sha256(authorization[7:].encode('utf8')).hexdigest()
        matched = None
        for name, client in self.clients.items():
            if hmac.compare_digest(digest, client['token_sha256']): matched = name
        if matched is None: raise Reject(401,'unauthorized')
        return matched

    def validate_zip(self, body):
        try:
            # Bound the central directory before ZipFile allocates ZipInfo objects.
            # Reports are small, single-disk, non-ZIP64 archives without comments.
            if len(body)<22 or body[-22:-18]!=b'PK\x05\x06': raise ValueError()
            if len(body)>=42 and body[-42:-38]==b'PK\x06\x07': raise ValueError()
            _,disk,central_disk,on_disk,count,central_size,central_offset,comment=struct.unpack_from('<4s4H2IH',body,len(body)-22)
            if disk or central_disk or comment or on_disk!=count or not 2<=count<=len(MEMBERS): raise ValueError()
            if central_size>4096 or central_offset+central_size!=len(body)-22: raise ValueError()
            with zipfile.ZipFile(io.BytesIO(body)) as z:
                members=z.infolist(); names=[x.filename for x in members]
                if len(names)!=count or len(names)!=len(set(names)) or not {'environment.txt','diagnostics.txt'}.issubset(names): raise ValueError()
                if not set(names).issubset(MEMBERS): raise ValueError()
                if sum(x.file_size for x in members)>64*1024*1024: raise ValueError()
                total=0
                for member in members:
                    if member.flag_bits & 1 or member.compress_type not in [zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED]: raise ValueError()
                    # Do not rely on ZipExtFile: it truncates output to the declared
                    # size, which can hide a forged ZIP bomb. Count real output.
                    offset=member.header_offset
                    header=struct.unpack_from('<4s5H3I2H',body,offset)
                    if header[0]!=b'PK\x03\x04' or header[2]!=member.flag_bits or header[3]!=member.compress_type: raise ValueError()
                    name_size,extra_size=header[-2:]
                    if body[offset+30:offset+30+name_size]!=member.filename.encode('ascii'): raise ValueError()
                    start=offset+30+name_size+extra_size;end=start+member.compress_size
                    if start<0 or end>central_offset: raise ValueError()
                    if not member.flag_bits & 8 and (header[6],header[7],header[8])!=(member.CRC,member.compress_size,member.file_size): raise ValueError()
                    checksum=0;expanded=0
                    decoder=zlib.decompressobj(-15) if member.compress_type==zipfile.ZIP_DEFLATED else None
                    cursor=start
                    while cursor<end:
                        chunk=body[cursor:min(end,cursor+65536)];cursor+=len(chunk)
                        while True:
                            output=decoder.decompress(chunk,65536) if decoder else chunk
                            expanded+=len(output);total+=len(output)
                            if expanded>member.file_size or total>64*1024*1024: raise ValueError()
                            checksum=zlib.crc32(output,checksum)
                            if not decoder or not decoder.unconsumed_tail: break
                            chunk=decoder.unconsumed_tail
                        if decoder and decoder.eof and (decoder.unused_data or cursor!=end): raise ValueError()
                    while decoder and not decoder.eof:
                        output=decoder.decompress(b'',65536)
                        expanded+=len(output);total+=len(output)
                        if expanded>member.file_size or total>64*1024*1024: raise ValueError()
                        checksum=zlib.crc32(output,checksum)
                        if not output:break
                    if decoder and not decoder.eof: raise ValueError()
                    if expanded!=member.file_size or checksum!=member.CRC: raise ValueError()
        except (ValueError,struct.error,zipfile.BadZipFile,zlib.error,RuntimeError,NotImplementedError,EOFError,OSError):
            raise Reject(422,'invalid_report_zip')

    def upload(self, env, digest, client):
        try: length=int(env.get('CONTENT_LENGTH',''))
        except ValueError: raise Reject(400,'invalid_length')
        if length<=0: raise Reject(400,'invalid_length')
        if length>self.max_bytes: raise Reject(413,'report_too_large')
        if env.get('CONTENT_TYPE')!='application/zip': raise Reject(415,'zip_required')
        source=env.get('HTTP_X_GUARD_SOURCE','')
        name=env.get('HTTP_X_GUARD_REPORT_NAME','')
        version=env.get('HTTP_X_GUARD_VERSION','')
        if source not in ['headunit','self-test'] or not REPORT_NAME.fullmatch(name) or not VERSION.fullmatch(version): raise Reject(400,'invalid_metadata')
        body=env['wsgi.input'].read(length)
        if len(body)!=length or hashlib.sha256(body).hexdigest()!=digest: raise Reject(422,'hash_mismatch')
        self.validate_zip(body)
        with self.lock:
            self.cleanup()
            directory=self.data/client
            if directory.is_symlink(): raise Reject(500,'storage_error')
            directory.mkdir(exist_ok=True)
            target=directory/(digest+'.zip')
            receipt={'ok':True,'id':client+'/'+digest,'sha256':digest,'bytes':length}
            if target.exists():
                if target.is_symlink() or hashlib.sha256(target.read_bytes()).hexdigest()!=digest: raise Reject(500,'storage_error')
                return 200,receipt
            now=time.time(); recent=self.accepted[client]
            while recent and recent[0]<now-3600: recent.popleft()
            if len(recent)>=self.hourly_limit: raise Reject(429,'rate_limit')
            used=sum(p.stat().st_size for p in self.data.glob('*/*.zip') if p.is_file() and not p.is_symlink())
            if used+length>self.quota_bytes: raise Reject(507,'storage_quota')
            temporary=directory/('.'+uuid.uuid4().hex+'.partial')
            metadata=directory/('.'+uuid.uuid4().hex+'.partial')
            try:
                with temporary.open('xb') as f:
                    f.write(body);f.flush();os.fsync(f.fileno())
                details=dict(receipt,app=APP,version=version,source=source,name=name,received_at_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()))
                with metadata.open('x',encoding='utf8') as f:
                    json.dump(details,f);f.flush();os.fsync(f.fileno())
                os.replace(metadata,target.with_suffix('.json'))
                os.replace(temporary,target)
                recent.append(now)
                # Persist directory entries on Linux before acknowledging the upload.
                if os.name!='nt':
                    fd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY)
                    try:os.fsync(fd)
                    finally:os.close(fd)
            finally:
                temporary.unlink(missing_ok=True);metadata.unlink(missing_ok=True)
            return 201,receipt

    def __call__(self, env, start_response):
        try:
            path=env.get('PATH_INFO','');method=env.get('REQUEST_METHOD','')
            if path=='/_health' and method=='GET': status,result=200,{'ok':True,'service':'voyah-guard-upload','protocol':1}
            elif path=='/_auth' and method=='GET':
                self.client(env.get('HTTP_AUTHORIZATION',''))
                status,result=204,{}
            else:
                match=re.fullmatch(re.escape(PREFIX)+r'reports/([0-9a-f]{64})',path)
                if not match or env.get('QUERY_STRING'): raise Reject(404,'not_found')
                if method!='PUT': raise Reject(405,'upload_only')
                client=self.client(env.get('HTTP_AUTHORIZATION',''))
                status,result=self.upload(env,match[1],client)
        except Reject as error: status,result=error.status,{'ok':False,'error':error.code}
        except Exception: status,result=500,{'ok':False,'error':'storage_error'}
        reasons={200:'OK',201:'Created',204:'No Content',400:'Bad Request',401:'Unauthorized',404:'Not Found',405:'Method Not Allowed',413:'Content Too Large',415:'Unsupported Media Type',422:'Unprocessable Content',429:'Too Many Requests',500:'Internal Server Error',507:'Insufficient Storage'}
        body=b'' if status==204 else json.dumps(result,separators=(',',':')).encode('utf8')
        start_response(str(status)+' '+reasons[status],[('Content-Type','application/json'),('Content-Length',str(len(body))),('Cache-Control','no-store')])
        return [body]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',required=True)
    parser.add_argument('--data',required=True)
    parser.add_argument('--port',type=int,default=18090)
    args=parser.parse_args()
    from waitress import serve
    receiver=Receiver(args.config,args.data)
    stop=threading.Event()
    worker=start_cleanup_worker(receiver,stop)
    try:
        serve(receiver,host='127.0.0.1',port=args.port,threads=2,
              connection_limit=32,channel_timeout=45,max_request_body_size=16*1024*1024,
              max_request_header_size=8192,clear_untrusted_proxy_headers=True)
    finally: stop.set();worker.join(timeout=2)

def start_cleanup_worker(receiver,stop,interval=3600):
    def sweep():
        while not stop.wait(interval):
            try: receiver.cleanup()
            except OSError: print('Guard report retention sweep failed',flush=True)
    thread=threading.Thread(target=sweep,daemon=True,name='guard-retention')
    thread.start()
    return thread

if __name__=='__main__': main()
