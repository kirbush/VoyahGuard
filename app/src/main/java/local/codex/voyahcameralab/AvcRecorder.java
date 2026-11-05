package local.codex.voyahcameralab;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** One surface encoder per recording; hidden chunks rotate without restarting capture. */
final class AvcRecorder {
  final Surface input;
  final TimestampClock clock;
  private final MediaCodec codec;
  private final VideoSession session;
  private final SegmentPolicy segments=new SegmentPolicy(SegmentPolicy.CHUNK_SECONDS);
  private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
  private MediaFormat outputFormat;
  private MediaMuxer muxer;
  private File pending;
  private long pendingStart;
  private int track=-1,samples,chunks;
  private boolean muxerStarted,closed,finishing,eosSignaled,segmentDamaged;
  private Exception recordingFailure;
  private long nextStorageCheck;

  AvcRecorder(VideoSession session,int width,int height) throws Exception {
    this.session=session;
    MediaCodec preparingCodec=null;Surface preparingSurface=null;
    try {
      MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE,6000000);
      format.setInteger(MediaFormat.KEY_FRAME_RATE,30);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
      format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
      String encoderName=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
      if(encoderName==null)throw new IOException("Нет H.264-кодировщика для полного размера кадра");
      preparingCodec=MediaCodec.createByCodecName(encoderName);
      preparingCodec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
      preparingSurface=preparingCodec.createInputSurface();preparingCodec.start();
      codec=preparingCodec;input=preparingSurface;clock=new TimestampClock(System.nanoTime());
      nextStorageCheck=SystemClock.elapsedRealtime()+1000;
    }catch(Exception failure){
      if(preparingSurface!=null)try{preparingSurface.release();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
      if(preparingCodec!=null)try{preparingCodec.release();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
      throw failure;
    }
  }

  void drain(boolean end) throws Exception {
    try {
      if(!finishing&&recordingFailure!=null)throw recordingFailure;
      if(!end&&SystemClock.elapsedRealtime()>=nextStorageCheck){
        nextStorageCheck=SystemClock.elapsedRealtime()+1000;session.checkSpace();
      }
      if(end&&!eosSignaled){codec.signalEndOfInputStream();eosSignaled=true;}
      long deadline=SystemClock.elapsedRealtime()+2500;
      while(true){
        if(end&&SystemClock.elapsedRealtime()>deadline)throw new IOException("Encoder EOS timeout");
        int index=codec.dequeueOutputBuffer(info,end?10000:0);
        if(index==MediaCodec.INFO_TRY_AGAIN_LATER){
          if(!end)return;
          if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Encoder EOS timeout");
          continue;
        }
        if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
          if(outputFormat!=null)throw new IOException("Encoder changed format twice");
          outputFormat=codec.getOutputFormat();continue;
        }
        if(index<0)continue;
        boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
        try {
          if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
            // A failed boundary drains output without creating another file.
            if(!segmentDamaged&&(recordingFailure==null||muxer!=null))write(codec.getOutputBuffer(index));
          }
        }finally{codec.releaseOutputBuffer(index,false);}
        if(eos)return;
      }
    }catch(Exception failure){if(recordingFailure==null)recordingFailure=failure;throw failure;}
  }

  private void write(ByteBuffer data) throws Exception {
    if(data==null||outputFormat==null)throw new IOException("Encoded data without encoder format");
    boolean keyframe=(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0;
    if(segments.rotate(info.presentationTimeUs,keyframe)){
      closeSegment();segments.next();
    }
    if(!segments.accepts(keyframe))return;
    if(muxer==null){
      pending=session.newChunk();pendingStart=SystemClock.elapsedRealtime();
      try {
        muxer=new MediaMuxer(pending.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        track=muxer.addTrack(outputFormat);muxer.start();muxerStarted=true;
      }catch(Exception failure){segmentDamaged=true;throw failure;}
    }
    MediaCodec.BufferInfo clipInfo=new MediaCodec.BufferInfo();
    clipInfo.set(info.offset,info.size,segments.timestamp(info.presentationTimeUs),info.flags);
    data.position(info.offset);data.limit(info.offset+info.size);
    try{muxer.writeSampleData(track,data,clipInfo);samples++;}
    catch(Exception failure){segmentDamaged=true;throw failure;}
  }

  /** Hand over only after a successful stop and release. */
  private void closeSegment() throws Exception {
    MediaMuxer closing=muxer;File closingFile=pending;
    boolean started=muxerStarted,valid=!segmentDamaged&&samples>0;
    muxer=null;pending=null;muxerStarted=false;segmentDamaged=false;samples=0;track=-1;
    Exception error=null;
    if(closing!=null){
      try{if(started)closing.stop();}catch(Exception failure){error=failure;}
      try{closing.release();}catch(Exception failure){error=merge(error,failure);}
    }
    if(closingFile==null){if(error!=null)throw error;return;}
    if(error!=null||!valid){session.discard(closingFile);if(error!=null)throw error;return;}
    try{session.chunk(closingFile,pendingStart,SystemClock.elapsedRealtime());chunks++;}
    catch(Exception failure){session.discard(closingFile);throw failure;}
  }

  void finish() throws Exception {
    if(closed)return;
    closed=true;finishing=true;segments.finish();Exception error=recordingFailure;
    try{drain(true);}catch(Exception failure){error=merge(error,failure);}
    try{closeSegment();}catch(Exception failure){error=merge(error,failure);}
    // Each cleanup is independent so one failure cannot strand the rest.
    try{codec.stop();}catch(Exception failure){error=merge(error,failure);}
    try{codec.release();}catch(Exception failure){error=merge(error,failure);}
    try{input.release();}catch(Exception failure){error=merge(error,failure);}
    session.finish();
    if(error!=null)throw error;
    if(chunks==0)throw new IOException("Запись не содержит закодированных кадров");
  }

  private static Exception merge(Exception first,Exception next){
    if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;
  }
}
