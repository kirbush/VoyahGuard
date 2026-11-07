"""Actual WSGI upload flow, no server mocks and no real diagnostic data."""
import concurrent.futures, hashlib, importlib.util, io, json, pathlib, tempfile, unittest, zipfile

SOURCE = pathlib.Path(__file__).resolve().parent / 'server.py'
spec = importlib.util.spec_from_file_location('guard_upload_receiver', SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def report(extra=None):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('environment.txt', 'Synthetic test, not a device report')
        z.writestr('diagnostics.txt', 'Guard test fixture')
        if extra: z.writestr(*extra)
    return out.getvalue()

class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.token = 'fixture-token-0123456789abcdefghijklmnopqrstuv'
        config = {'clients': {'tester-one': {'token_sha256': hashlib.sha256(self.token.encode()).hexdigest()}},
                  'max_bytes': 1024*1024, 'quota_bytes': 4*1024*1024, 'hourly_limit': 100, 'retention_days': 30}
        self.config = self.root/'clients.json'
        self.config.write_text(json.dumps(config))
        self.app = module.Receiver(self.config, self.root/'reports')

    def tearDown(self): self.temp.cleanup()

    def call(self, data=b'', method='PUT', path=None, token=True, **headers):
        digest = hashlib.sha256(data).hexdigest()
        env = {'REQUEST_METHOD': method, 'PATH_INFO': path or '/voyah-guard/v1/reports/'+digest,
               'QUERY_STRING': '', 'CONTENT_LENGTH': str(len(data)), 'CONTENT_TYPE': 'application/zip',
               'wsgi.input': io.BytesIO(data), 'HTTP_X_GUARD_SOURCE': 'headunit', 'HTTP_X_GUARD_VERSION': '0.8-alpha',
               'HTTP_X_GUARD_REPORT_NAME': 'Voyah-Guard-report-20261107-221500.zip'}
        if token: env['HTTP_AUTHORIZATION'] = 'Bearer '+self.token
        env.update(headers)
        response = []
        body = b''.join(self.app(env, lambda status, h: response.append((status,h))))
        return int(response[0][0].split()[0]), json.loads(body) if body else {}, dict(response[0][1])

    def test_internal_auth_can_reject_before_reading_a_body(self):
        class NoBody:
            def read(self,*args): raise AssertionError('Unauthorized request body was read')
        self.assertEqual(self.call(path='/_auth',method='GET',token=False,**{'wsgi.input':NoBody()})[0],401)
        self.assertEqual(self.call(path='/_auth',method='GET',**{'wsgi.input':NoBody()})[0],204)
        self.assertEqual(self.call(path='/voyah-guard/v1/health',method='GET')[0],404)

    def test_zip_bomb_metadata_and_invalid_deflate_are_rejected(self):
        import struct
        bomb=bytearray(report());central=bomb.index(b'PK\x01\x02')
        struct.pack_into('<I',bomb,central+24,70*1024*1024)
        self.assertEqual(self.call(bytes(bomb))[0],422)
        corrupt=bytearray(report());start=corrupt.index(b'PK\x03\x04')
        n,e=struct.unpack_from('<HH',corrupt,start+26)
        corrupt[start+30+n+e:start+30+n+e+4]=b'\xff\xff\xff\xff'
        self.assertEqual(self.call(bytes(corrupt))[0],422)

    def test_forged_small_size_cannot_hide_actual_zip_bomb(self):
        import struct
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
            with z.open('environment.txt','w') as f:
                for _ in range(70):f.write(b'x'*(1024*1024))
            z.writestr('diagnostics.txt',b'')
        body=bytearray(out.getvalue());central=body.index(b'PK\x01\x02')
        struct.pack_into('<I',body,central+16,0);struct.pack_into('<I',body,central+24,0)
        struct.pack_into('<I',body,14,0);struct.pack_into('<I',body,22,0)
        self.assertEqual(self.call(bytes(body))[0],422)

    def test_excessive_directory_is_rejected_before_zipfile_parses_it(self):
        import struct,unittest.mock
        body=bytearray(report());struct.pack_into('<H',body,len(body)-12,65535)
        with unittest.mock.patch.object(module.zipfile,'ZipFile',side_effect=AssertionError('Unbounded directory parsed')):
            self.assertEqual(self.call(bytes(body))[0],422)

    def test_zip64_cannot_override_bounded_classic_directory(self):
        import struct,unittest.mock
        original=report();end=len(original)-22
        zip64=struct.pack('<4sQ2H2I4Q',b'PK\x06\x06',44,45,45,0,0,275000,275000,end,0)
        locator=struct.pack('<4sIQI',b'PK\x06\x07',0,end,1)
        fake_end=struct.pack('<4s4H2IH',b'PK\x05\x06',0,0,2,2,76,end+len(zip64)+len(locator)-76,0)
        body=original[:end]+zip64+locator+fake_end
        with unittest.mock.patch.object(module.zipfile,'ZipFile',side_effect=AssertionError('ZIP64 bypass parsed')):
            self.assertEqual(self.call(body)[0],422)

    def test_fm_reports_and_fm_paths_are_not_accepted_here(self):
        self.assertEqual(self.call(report(),path='/voyah-fm/v1/reports/'+hashlib.sha256(report()).hexdigest())[0],404)
        self.assertEqual(self.call(report(('collection.txt','pc only')))[0],422)

    def test_metadata_marks_the_app(self):
        data=report(); self.assertEqual(self.call(data)[0],201)
        meta=json.loads(next((self.root/'reports').rglob('*.json')).read_text())
        self.assertEqual((meta['app'],meta['version'],meta['source']),('voyah-guard','0.8-alpha','headunit'))

    def test_valid_and_idempotent(self):
        data = report(); digest = hashlib.sha256(data).hexdigest()
        first = self.call(data); again = self.call(data)
        self.assertEqual(first[0], 201); self.assertEqual(again[0], 200)
        self.assertEqual(first[1]['sha256'], digest)
        self.assertEqual(first[1]['id'], again[1]['id'])
        files = list((self.root/'reports').rglob('*.zip'))
        self.assertEqual(len(files), 1); self.assertEqual(files[0].read_bytes(), data)
        self.assertEqual(first[2]['Cache-Control'], 'no-store')

    def test_valid_deflate_output_block_boundaries(self):
        for level in [1,6,9]:
            for length in [65535,65536,65537,131073,1048577]:
                out=io.BytesIO()
                with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=level) as z:
                    z.writestr('environment.txt',b'fixture')
                    z.writestr('diagnostics.txt',b'0'*length)
                self.assertIn(self.call(out.getvalue())[0],[200,201],(level,length))

    def test_auth_required_even_for_duplicate(self):
        data=report(); self.call(data)
        self.assertEqual(self.call(data, token=False)[0], 401)
        self.assertEqual(self.call(data, HTTP_AUTHORIZATION='Bearer wrong')[0], 401)

    def test_no_read_list_delete_or_traversal(self):
        for method in ['GET', 'DELETE', 'POST']:
            self.assertEqual(self.call(report(), method=method)[0], 405)
        for path in ['/voyah-guard/v1/reports/', '/voyah-guard/v1/reports/../../secret', '/etc/passwd']:
            self.assertEqual(self.call(report(), path=path)[0], 404)

    def test_hash_and_corruption_rejected(self):
        self.assertEqual(self.call(report(), path='/voyah-guard/v1/reports/'+'0'*64)[0], 422)
        self.assertEqual(self.call(b'not a zip')[0], 422)
        self.assertFalse(list((self.root/'reports').rglob('*.zip')))

    def test_unexpected_zip_members_and_missing_evidence(self):
        for member in ['../escape.txt', '/absolute', 'unrelated-secret.txt']:
            self.assertEqual(self.call(report((member,'bad')))[0], 422)
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w') as z:z.writestr('only.txt','not a Guard report')
        self.assertEqual(self.call(out.getvalue())[0],422)

    def test_length_type_and_source(self):
        self.assertEqual(self.call(report(),CONTENT_LENGTH='-1')[0],400)
        self.assertEqual(self.call(report(),CONTENT_LENGTH='999999999')[0],413)
        self.assertEqual(self.call(report(),CONTENT_LENGTH='1')[0],422)
        self.assertEqual(self.call(report(),CONTENT_TYPE='text/plain')[0],415)
        self.assertEqual(self.call(report(),HTTP_X_GUARD_SOURCE='../../bad')[0],400)
        self.assertEqual(self.call(report(),HTTP_X_GUARD_SOURCE='pc')[0],400)
        self.assertEqual(self.call(report(),HTTP_X_GUARD_VERSION='')[0],400)
        self.assertEqual(self.call(report(),HTTP_X_GUARD_VERSION='0.8 alpha/../')[0],400)
        self.assertEqual(self.call(report(),HTTP_X_GUARD_REPORT_NAME='Voyah-FM-report-20261107-221500.zip')[0],400)

    def test_quota_and_rate_limit_preserve_accepted_report(self):
        data=report(); self.app.quota_bytes=len(data)
        self.assertEqual(self.call(data)[0],201)
        self.assertEqual(self.call(report(('events.log','different')))[0],507)
        self.assertEqual(self.call(data)[0],200)
        self.app.hourly_limit=1
        self.assertEqual(self.call(report(('events.log','third')))[0],429)

    def test_retention_only_deletes_own_completed_reports(self):
        self.call(report()); files=list((self.root/'reports').rglob('*.zip'))
        import os,time
        os.utime(files[0],(time.time()-40*86400,time.time()-40*86400))
        unrelated=self.root/'reports'/'do-not-touch.txt';unrelated.write_text('keep')
        self.app.cleanup()
        self.assertFalse(files[0].exists());self.assertTrue(unrelated.exists())

    def test_idle_retention_does_not_depend_on_future_uploads(self):
        import os,threading,time
        self.call(report());file=list((self.root/'reports').rglob('*.zip'))[0]
        os.utime(file,(time.time()-40*86400,time.time()-40*86400))
        stop=threading.Event();worker=module.start_cleanup_worker(self.app,stop,interval=0.01)
        try:
            deadline=time.monotonic()+2
            while file.exists() and time.monotonic()<deadline:stop.wait(0.01)
            self.assertFalse(file.exists())
        finally:stop.set();worker.join(timeout=1)

    def test_parallel_duplicate_has_one_report_and_receipt(self):
        data=report()
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(lambda _:self.call(data),range(8)))
        self.assertEqual(sum(x[0]==201 for x in results),1)
        self.assertTrue(all(x[0] in [200,201] for x in results))
        self.assertEqual(len(list((self.root/'reports').rglob('*.zip'))),1)

if __name__=='__main__':unittest.main()
