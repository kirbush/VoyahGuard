package local.codex.voyahcameralab;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Size;

final class CaptureSizes {
  static Size selectSize(CameraCharacteristics c,String cameraId,boolean paddingRequested) {
    StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    Size[] values=map==null?null:map.getOutputSizes(SurfaceTexture.class);
    if(values==null||values.length==0)throw new IllegalStateException("No preview output sizes");
    Size chosen=null;
    Integer rotation=c.get(CameraCharacteristics.SENSOR_ORIENTATION);
    for(Size size:values){long area=(long)size.getWidth()*size.getHeight();int w=size.getWidth(),h=size.getHeight();FramePolicy output=FramePolicy.forCapture(cameraId,w,h,rotation==null?0:rotation,paddingRequested);if(w<=1920&&h<=1440&&supportsAvc(output.outputWidth,output.outputHeight)&&(chosen==null||area>(long)chosen.getWidth()*chosen.getHeight()))chosen=size;}
    if(chosen==null)throw new IllegalStateException("No full-frame preview size supported by H.264 encoder (maximum 1920x1440)");
    return chosen;
  }
  private static boolean supportsAvc(int width,int height){
    for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
      if(!info.isEncoder())continue;
      try{MediaCodecInfo.CodecCapabilities cap=info.getCapabilitiesForType("video/avc");boolean surface=false;for(int color:cap.colorFormats)if(color==MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)surface=true;if(surface&&cap.getVideoCapabilities().areSizeAndRateSupported(width,height,30))return true;}catch(IllegalArgumentException ignored){}
    }
    return false;
  }
}
