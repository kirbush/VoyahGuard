package local.codex.voyahcameralab;
final class FramePolicy {
  final int outputWidth,outputHeight,rotation;
  final float cropBottom,cropTop;
  final boolean paddingCorrected;
  static FramePolicy fullFrame(int width,int height,int rotation){return new FramePolicy(width,height,rotation,false);}
  static FramePolicy forCapture(String cameraId,int width,int height,int rotation,boolean correctionRequested){
    boolean knownProfile="1".equals(cameraId)&&width==1920&&(height==720||height==1080||height==1200);
    return new FramePolicy(width,height,rotation,correctionRequested&&knownProfile);
  }
  private FramePolicy(int width,int height,int rotation,boolean paddingCorrected){
    if(width<=0||height<=0)throw new IllegalArgumentException("Invalid image size");
    this.rotation=((rotation%360)+360)%360;
    this.paddingCorrected=paddingCorrected;
    // Logical texture top is v=1 before the SurfaceTexture producer transform.
    cropBottom=paddingCorrected?0.4f:0f;cropTop=1f;
    int visibleHeight=paddingCorrected?720:height;
    boolean swap=this.rotation==90||this.rotation==270;
    this.outputWidth=(swap?visibleHeight:width)&~1;
    this.outputHeight=(swap?width:visibleHeight)&~1;
  }
  int[] fit(int width,int height){
    if(width<1||height<1)return new int[]{0,0,1,1};
    double scale=Math.min((double)width/outputWidth,(double)height/outputHeight);
    int w=Math.max(1,(int)Math.round(outputWidth*scale)),h=Math.max(1,(int)Math.round(outputHeight*scale));
    return new int[]{(width-w)/2,(height-h)/2,w,h};
  }
}
