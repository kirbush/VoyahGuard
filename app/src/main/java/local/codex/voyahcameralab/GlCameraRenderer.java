package local.codex.voyahcameralab;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.opengl.*;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.*;

final class GlCameraRenderer {
  interface Listener {void onFrame(long count);void onError(String error);void onSegmentSaved(Uri uri);void onRecordingFinished(Uri uri,String error);}
  /** Receives small top-down RGBA copies of the output frame on the capture thread. */
  interface FrameSampler {boolean wantsFrame();byte[] frameBuffer();void frameReady(byte[] rgba);void samplerFailed(String error);}
  private static final long SAMPLE_INTERVAL_MS=200;
  final Surface cameraSurface;
  final FramePolicy policy;
  private final Context context;private final Listener listener;private final SurfaceTexture cameraTexture;
  private final Surface displaySurface;private EGLDisplay display;private EGLContext eglContext;private EGLConfig config;
  private EGLSurface preview,encoder=EGL14.EGL_NO_SURFACE;private AvcRecorder recorder;private Uri lastSaved;
  private final float[] transform=new float[16];private final FloatBuffer vertices;
  private int program,texture,position,coordinates,matrix,vertical,rotation,width,height,sampleFramebuffer,sampleTexture;private long frames,lastReport,lastSample;private boolean closed;
  private volatile FrameSampler sampler;private ByteBuffer samplePixels;
  GlCameraRenderer(Context context,Handler handler,SurfaceTexture output,int windowWidth,int windowHeight,int inputWidth,int inputHeight,FramePolicy policy,Listener listener) throws Exception {
    this.context=context;this.listener=listener;this.policy=policy;width=windowWidth;height=windowHeight;
    displaySurface=new Surface(output);
    display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] version=new int[2];if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("EGL initialize");
    int[] attributes={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE};
    EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
    if(!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)||count[0]==0)throw new IllegalStateException("No recordable EGL config");
    config=configs[0];eglContext=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
    preview=EGL14.eglCreateWindowSurface(display,config,displaySurface,new int[]{EGL14.EGL_NONE},0);makeCurrent(preview);clearPreview();
    int[] textures=new int[1];GLES20.glGenTextures(1,textures,0);texture=textures[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
    String vertex="attribute vec2 aPosition;attribute vec2 aTex;varying vec2 vTex;void main(){gl_Position=vec4(aPosition,0.,1.);vTex=aTex;}";
    String fragment="#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES image;uniform mat4 uMatrix;uniform vec2 uVertical;uniform float uRotation;varying vec2 vTex;void main(){vec2 uv=vTex;if(uRotation==90.)uv=vec2(vTex.y,1.-vTex.x);else if(uRotation==180.)uv=vec2(1.-vTex.x,1.-vTex.y);else if(uRotation==270.)uv=vec2(1.-vTex.y,vTex.x);uv.y=uVertical.x+uv.y*(uVertical.y-uVertical.x);vec2 sampleAt=(uMatrix*vec4(uv,0.,1.)).xy;gl_FragColor=texture2D(image,sampleAt);}";
    int v=shader(GLES20.GL_VERTEX_SHADER,vertex),f=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
    program=GLES20.glCreateProgram();GLES20.glAttachShader(program,v);GLES20.glAttachShader(program,f);GLES20.glLinkProgram(program);
    int[] linked=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);if(linked[0]!=GLES20.GL_TRUE)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
    GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);
    position=GLES20.glGetAttribLocation(program,"aPosition");coordinates=GLES20.glGetAttribLocation(program,"aTex");matrix=GLES20.glGetUniformLocation(program,"uMatrix");vertical=GLES20.glGetUniformLocation(program,"uVertical");rotation=GLES20.glGetUniformLocation(program,"uRotation");
    vertices=ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    vertices.put(new float[]{-1,-1,0,0,1,-1,1,0,-1,1,0,1,1,1,1,1}).position(0);
    cameraTexture=new SurfaceTexture(texture);cameraTexture.setDefaultBufferSize(inputWidth,inputHeight);
    cameraSurface=new Surface(cameraTexture);
    cameraTexture.setOnFrameAvailableListener(unused->frame(),handler);
  }
  private static int shader(int type,String code){int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,code);GLES20.glCompileShader(shader);int[] valid=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,valid,0);if(valid[0]!=GLES20.GL_TRUE)throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));return shader;}
  private void makeCurrent(EGLSurface surface){if(surface==null||surface==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(display,surface,surface,eglContext))throw new IllegalStateException("EGL makeCurrent 0x"+Integer.toHexString(EGL14.eglGetError()));}
  private void clearPreview(){GLES20.glViewport(0,0,width,height);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);EGL14.eglSwapBuffers(display,preview);}
  void resize(int width,int height){this.width=width;this.height=height;}
  private void draw(int width,int height,boolean fit){
    GLES20.glViewport(0,0,width,height);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    int[] viewport=fit?policy.fit(width,height):new int[]{0,0,width,height,0,width};
    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);GLES20.glScissor(viewport[4],viewport[1],viewport[5],viewport[3]);
    drawQuad(viewport[0],viewport[1],viewport[2],viewport[3]);GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
  }
  private void drawQuad(int x,int y,int width,int height){
    GLES20.glViewport(x,y,width,height);GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
    vertices.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(position);
    vertices.position(2);GLES20.glVertexAttribPointer(coordinates,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(coordinates);
    GLES20.glUniformMatrix4fv(matrix,1,false,transform,0);GLES20.glUniform1f(rotation,policy.rotation);GLES20.glUniform2f(vertical,policy.cropBottom,policy.cropTop);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR)throw new IllegalStateException("GL error 0x"+Integer.toHexString(error));
  }
  private void frame(){
    if(closed)return;
    try {
      makeCurrent(preview);cameraTexture.updateTexImage();cameraTexture.getTransformMatrix(transform);
      draw(width,height,true);if(!EGL14.eglSwapBuffers(display,preview))throw new IllegalStateException("Preview swap failed");
      if(recorder!=null){makeCurrent(encoder);draw(policy.outputWidth,policy.outputHeight,false);EGLExt.eglPresentationTimeANDROID(display,encoder,recorder.clock.next(System.nanoTime())*1000);if(!EGL14.eglSwapBuffers(display,encoder))throw new IllegalStateException("Encoder swap failed");recorder.drain(false);}
      sample();
      frames++;long now=SystemClock.elapsedRealtime();if(FrameHealth.reportDue(frames,now,lastReport)){lastReport=now;listener.onFrame(frames);}
    }catch(Exception failure){String error=failure instanceof java.io.IOException?failure.getMessage():"GPU/encoder: "+failure;if(recorder!=null)stopRecording(error);listener.onError(error);}
  }
  void setSampler(FrameSampler sampler){this.sampler=sampler;}
  /** One bounded readback every SAMPLE_INTERVAL_MS. A failure stops sampling, never the recording. */
  private void sample(){
    FrameSampler target=sampler;if(target==null||!target.wantsFrame())return;
    long now=SystemClock.elapsedRealtime();if(now-lastSample<SAMPLE_INTERVAL_MS)return;lastSample=now;
    int w=AvmLayout.SAMPLE_WIDTH,h=AvmLayout.BUFFER_HEIGHT,stride=w*4,glyphH=AvmLayout.GLYPH_HEIGHT;
    try{
      makeCurrent(preview);if(sampleFramebuffer==0)createSampleTarget(w,h);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,sampleFramebuffer);
      drawQuad(0,glyphH,w,AvmLayout.SAMPLE_HEIGHT); // Bottom-up target: the rows above the glyph strip.
      GLES20.glEnable(GLES20.GL_SCISSOR_TEST);GLES20.glScissor(0,0,AvmLayout.GLYPH_WIDTH,glyphH);
      drawQuad(-AvmLayout.GLYPH_X,-(policy.outputHeight-AvmLayout.GLYPH_Y-glyphH),policy.outputWidth,policy.outputHeight);
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
      samplePixels.position(0);GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,samplePixels);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
      byte[] rgba=target.frameBuffer();
      for(int y=0;y<h;y++){samplePixels.position((h-1-y)*stride);samplePixels.get(rgba,y*stride,stride);}
      target.frameReady(rgba);
    }catch(RuntimeException failure){GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);sampler=null;target.samplerFailed(failure.toString());}
  }
  private void createSampleTarget(int w,int h){
    int[] id=new int[1];GLES20.glGenTextures(1,id,0);sampleTexture=id[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,sampleTexture);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
    GLES20.glGenFramebuffers(1,id,0);sampleFramebuffer=id[0];GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,sampleFramebuffer);
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,sampleTexture,0);
    int complete=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
    if(complete!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Sample framebuffer 0x"+Integer.toHexString(complete));
    samplePixels=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());
  }
  boolean isRecording(){return recorder!=null;}
  void startRecording(String cameraId,int segmentSeconds) throws Exception {
    if(closed||recorder!=null)throw new IllegalStateException("Recording not available");
    makeCurrent(preview);lastSaved=null;recorder=new AvcRecorder(context,policy.outputWidth,policy.outputHeight,cameraId,segmentSeconds,uri->{lastSaved=uri;listener.onSegmentSaved(uri);});
    try{encoder=EGL14.eglCreateWindowSurface(display,config,recorder.input,new int[]{EGL14.EGL_NONE},0);if(encoder==EGL14.EGL_NO_SURFACE)throw new IllegalStateException("Encoder EGL surface");}
    catch(Exception failure){try{recorder.finish();}catch(Exception ignored){}recorder=null;throw failure;}
  }
  void stopRecording(){stopRecording(null);}
  private void stopRecording(String cause){
    if(recorder==null)return;AvcRecorder finished=recorder;recorder=null;
    try{makeCurrent(preview);}catch(Exception ignored){}
    if(encoder!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,encoder);encoder=EGL14.EGL_NO_SURFACE;
    String failure=cause;try{Uri uri=finished.finish();if(uri!=null)lastSaved=uri;}catch(Exception error){if(failure==null)failure=error.toString();}
    listener.onRecordingFinished(lastSaved,failure);
  }
  void close(){
    if(closed)return;closed=true;sampler=null;stopRecording();
    cameraTexture.setOnFrameAvailableListener(null);cameraSurface.release();cameraTexture.release();
    try{makeCurrent(preview);clearPreview();if(sampleFramebuffer!=0){GLES20.glDeleteFramebuffers(1,new int[]{sampleFramebuffer},0);GLES20.glDeleteTextures(1,new int[]{sampleTexture},0);}GLES20.glDeleteTextures(1,new int[]{texture},0);GLES20.glDeleteProgram(program);}catch(Exception ignored){}
    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
    EGL14.eglDestroySurface(display,preview);EGL14.eglDestroyContext(display,eglContext);EGL14.eglReleaseThread();EGL14.eglTerminate(display);displaySurface.release();
  }
}
