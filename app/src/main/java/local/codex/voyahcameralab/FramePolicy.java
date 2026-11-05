package local.codex.voyahcameralab;
final class FramePolicy {
  final int outputWidth,outputHeight,rotation;
  final float cropBottom,cropTop;
  final boolean paddingCorrected,anamorphic;
  private static final int AVM_TOOLBAR_WIDTH=160; // Columns that hold OEM 360 buttons, never camera image.
  private final int displayWidth,displayHeight,hiddenLeft;
  static FramePolicy fullFrame(int width,int height,int rotation){return new FramePolicy(width,height,rotation,false,false);}
  static FramePolicy forCapture(String cameraId,int width,int height,int rotation,boolean correctionRequested){
    boolean knownProfile="1".equals(cameraId)&&width==1920&&(height==720||height==1080||height==1200);
    boolean padding=correctionRequested&&knownProfile;
    // The full 1920×1200 AVM frame is authored for the 1920×720 screen: its pixels are 5:3 tall.
    // Measured on the car outline: 2.48:1 restored against 2.52:1 of the real body.
    // The manual green-padding crop leaves the same 1920×720 picture, so everything else applies.
    boolean anamorphic="1".equals(cameraId)&&width==1920&&rotation%360==0&&(padding||height==1200);
    return new FramePolicy(width,height,rotation,padding,anamorphic);
  }
  private FramePolicy(int width,int height,int rotation,boolean paddingCorrected,boolean anamorphic){
    if(width<=0||height<=0)throw new IllegalArgumentException("Invalid image size");
    this.rotation=((rotation%360)+360)%360;
    this.paddingCorrected=paddingCorrected;this.anamorphic=anamorphic;
    // Logical texture top is v=1 before the SurfaceTexture producer transform.
    cropBottom=paddingCorrected?0.4f:0f;cropTop=1f;
    int visibleHeight=paddingCorrected||anamorphic?720:height; // Files must play in true proportions everywhere.
    boolean swap=this.rotation==90||this.rotation==270;
    this.outputWidth=(swap?visibleHeight:width)&~1;
    this.outputHeight=(swap?width:visibleHeight)&~1;
    displayWidth=outputWidth;displayHeight=outputHeight;hiddenLeft=anamorphic?AVM_TOOLBAR_WIDTH:0;
  }
  /**
   * Preview only; recording always keeps the full output width, OEM toolbar included.
   * @return x, y, width, height of the whole frame, then clipX, clipWidth of its visible columns.
   */
  int[] fit(int width,int height){
    if(width<1||height<1)return new int[]{0,0,1,1,0,1};
    int visibleWidth=displayWidth-hiddenLeft;
    double scale=Math.min((double)width/visibleWidth,(double)height/displayHeight);
    int w=Math.max(1,(int)Math.round(displayWidth*scale)),h=Math.max(1,(int)Math.round(displayHeight*scale));
    int clipWidth=Math.max(1,(int)Math.round(visibleWidth*scale)),clipX=(width-clipWidth)/2;
    return new int[]{clipX-(w-clipWidth),(height-h)/2,w,h,clipX,clipWidth};
  }
}
