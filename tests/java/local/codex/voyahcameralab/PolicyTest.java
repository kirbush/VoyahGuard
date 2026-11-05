package local.codex.voyahcameralab;
public final class PolicyTest {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    FramePolicy raw=FramePolicy.fullFrame(1920,1080,0);
    check(raw.outputWidth==1920&&raw.outputHeight==1080,"Lite keeps every row of the delivered AVM frame");
    FramePolicy rawTall=FramePolicy.fullFrame(1920,1440,90);
    check(rawTall.outputWidth==1440&&rawTall.outputHeight==1920,"Full-frame rotation preserves tall four-view geometry");
    FramePolicy normal=FramePolicy.fullFrame(1280,720,0);
    check(normal.outputWidth==1280&&normal.outputHeight==720,"Normal camera dimensions");
    FramePolicy full=FramePolicy.fullFrame(1920,720,0);
    check(full.outputWidth==1920&&full.outputHeight==720,"Ordinary360 fills1920px; do not assume APA padding");
    FramePolicy tall=FramePolicy.fullFrame(1920,1200,0);
    check(tall.outputWidth==1920&&tall.outputHeight==1200,"Full-frame source is never normalized to 720 rows");
    check(raw.cropBottom==0f&&raw.cropTop==1f&&!raw.paddingCorrected,"Default keeps every source row and does not apply the experimental profile");
    FramePolicy disabled=FramePolicy.forCapture("1",1920,1200,0,false);
    check(disabled.outputHeight==1200&&!disabled.paddingCorrected&&disabled.cropBottom==0f,"Correction requires explicit opt-in");
    for(int height:new int[]{720,1080,1200}){
      FramePolicy corrected=FramePolicy.forCapture("1",1920,height,0,true);
      check(corrected.paddingCorrected&&corrected.outputWidth==1920&&corrected.outputHeight==720,"Known legacy inputs restore 1920x720 only when requested");
      check(Math.abs(corrected.cropBottom-0.4f)<0.00001f&&corrected.cropTop==1f,"Legacy profile samples the top 60 percent before producer transform");
    }
    FramePolicy corrected=FramePolicy.forCapture("1",1920,1200,0,true);
    int firstSourceRow=(int)(1200*(1-(corrected.cropBottom+(1-0.5/corrected.outputHeight)*(corrected.cropTop-corrected.cropBottom))));
    int lastSourceRow=(int)(1200*(1-(corrected.cropBottom+(0.5/corrected.outputHeight)*(corrected.cropTop-corrected.cropBottom))));
    check(firstSourceRow==0&&lastSourceRow==719,"Legacy profile retains source rows 0..719 and excludes lower filler in the historical 1200-row buffer");
    FramePolicy correctedRotated=FramePolicy.forCapture("1",1920,1080,90,true);
    check(correctedRotated.outputWidth==720&&correctedRotated.outputHeight==1920,"Legacy vertical correction preserves full width and quarter-turn geometry");
    FramePolicy unsupportedTall=FramePolicy.forCapture("1",1920,1440,0,true);
    check(!unsupportedTall.paddingCorrected&&unsupportedTall.cropBottom==0f&&unsupportedTall.outputHeight==1440,"Unknown four-view geometry stays full-frame even with correction requested");
    FramePolicy unsupportedWidth=FramePolicy.forCapture("1",1280,720,0,true);
    check(!unsupportedWidth.paddingCorrected&&unsupportedWidth.cropBottom==0f&&unsupportedWidth.outputWidth==1280,"Other widths stay full-frame");
    FramePolicy unsupportedSource=FramePolicy.forCapture("0",1920,1080,0,true);
    check(!unsupportedSource.paddingCorrected&&unsupportedSource.cropBottom==0f&&unsupportedSource.outputHeight==1080,"Other Camera2 inputs stay full-frame");
    FramePolicy rotated=FramePolicy.fullFrame(1280,720,90);
    check(rotated.outputWidth==720&&rotated.outputHeight==1280,"Portrait sensor rotation");
    FramePolicy upsideDown=FramePolicy.fullFrame(1280,720,180);
    check(upsideDown.outputWidth==1280&&upsideDown.outputHeight==720&&upsideDown.rotation==180,"Half-turn preserves source geometry");
    FramePolicy reversePortrait=FramePolicy.fullFrame(1280,720,-90);
    check(reversePortrait.outputWidth==720&&reversePortrait.outputHeight==1280&&reversePortrait.rotation==270,"Negative rotation normalizes to a full-frame quarter-turn");
    check(FramePolicy.fullFrame(1280,720,450).rotation==90,"Extra turns normalize");
    int[] fit=raw.fit(1000,600);
    check(fit[0]==0&&fit[1]==18&&fit[2]==1000&&fit[3]==563,"Letterbox without distortion");
    int[] portraitFit=rotated.fit(1000,600);
    check(portraitFit[0]==331&&portraitFit[1]==0&&portraitFit[2]==338&&portraitFit[3]==600,"Portrait source is pillarboxed without distortion");
    FramePolicy avm=FramePolicy.forCapture("1",1920,1200,0,false);
    check(avm.anamorphic&&avm.outputWidth==1920&&avm.outputHeight==1200,"Recording keeps the full AVM frame and its buttons");
    int[] avmFit=avm.fit(1760,720);
    check(avmFit[0]==-160&&avmFit[1]==0&&avmFit[2]==1920&&avmFit[3]==720,"AVM preview restores 8:3 and pushes the toolbar off the left edge");
    check(avmFit[4]==0&&avmFit[5]==1760,"Only the picture columns remain visible");
    int[] avmWide=avm.fit(1770,675);
    check(avmWide[4]==60&&avmWide[5]==1650&&avmWide[2]==1800&&avmWide[0]==-90,"The clip hides the toolbar inside a pillarboxed window too");
    check(FramePolicy.forCapture("1",1920,1080,0,false).fit(1920,1080)[4]==0,"Other sizes stay uncropped");
    TimestampClock time=new TimestampClock(1000000000L);
    check(time.next(1000000000L)==0,"First timestamp");
    check(time.next(1000000000L)==1,"Duplicate clock must advance");
    check(time.next(999999999L)==2,"Backward clock must not reverse presentation");
    check(time.next(1033333000L)==33333,"Elapsed-time based media duration");
    CaptureGate gate=new CaptureGate();int old=gate.begin();
    check(gate.accepts(old),"Current open request");gate.cancel();
    check(!gate.accepts(old),"Late onOpened after close must be rejected");
    int current=gate.begin();check(!gate.accepts(old)&&gate.accepts(current),"Late callback cannot replace a newer session");
    FrameHealth health=new FrameHealth();check(!health.stalled(8000),"Initial no-frame timeout is separate");health.frame(9000);check(!health.stalled(13000)&&health.stalled(14000),"Interrupted stream becomes visible after five seconds");health.frame(15000);check(!health.stalled(15000),"Recovered frames clear stream interruption");health.reset();check(health.age(16000)==-1,"Closed stream forgets old frame time");
    for(int intervalMs:new int[]{33,200,500,1500}){
      FrameHealth slow=new FrameHealth();long lastReport=0;
      for(long frame=1,now=10000;frame<=400;frame++,now+=intervalMs){
        if(FrameHealth.reportDue(frame,now,lastReport)){lastReport=now;slow.frame(now);}
        check(!slow.stalled(now),"A slow live stream is not a stall: "+intervalMs+" ms between frames");
      }
    }
    check(FrameHealth.reportDue(1,5,4)&&FrameHealth.reportDue(3,5,4),"Frames one and three always reach the UI for the start gate");
    check(!FrameHealth.reportDue(4,1033,1000),"A normal stream is not reported on every frame");
    System.out.println("PASS: full-frame geometry, timestamps, late callbacks and frame-stall recovery");
  }
}
