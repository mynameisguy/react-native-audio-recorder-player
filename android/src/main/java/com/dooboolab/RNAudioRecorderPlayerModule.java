
package com.dooboolab;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Callback;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.media.MediaPlayer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.PermissionChecker;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.PermissionListener;

import org.json.JSONException;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.Nullable;

public class RNAudioRecorderPlayerModule extends ReactContextBaseJavaModule implements PermissionListener{
  final private static String TAG = "RNAudioRecorderPlayer";
  final private static String FILE_LOCATION = "sdcard/sound.mp4";
  private String audioFileURL = "";

  private int subsDurationMillis = 100;
  private boolean _meteringEnabled = false;

  private final ReactApplicationContext reactContext;
  private MediaRecorder mediaRecorder;
  private MediaPlayer mediaPlayer;

  private Visualizer mVisualizer;
  private Visualizer.MeasurementPeakRms mMeasurementPeakRms = new Visualizer.MeasurementPeakRms();

  int counterPlayer = 0;
  static double[] drawingBufferForPlayer = new double[100];
  private byte[] mBytes;
  private byte[] mFFTBytes;

  private Runnable recorderRunnable;
  private TimerTask mTask;
  private Timer mTimer;
  Handler recordHandler = new Handler(Looper.getMainLooper());

  public RNAudioRecorderPlayerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @ReactMethod
  public void startRecorder(final String path, final Boolean meteringEnabled, final ReadableMap audioSet, Promise promise) {
    try {
      if (
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
              (
                  ActivityCompat.checkSelfPermission(reactContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                  ActivityCompat.checkSelfPermission(reactContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
              )
          ) {
        ActivityCompat.requestPermissions(getCurrentActivity(), new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        }, 0);
        promise.reject("No permission granted.", "Try again after adding permission.");
        return;
      }
    } catch (NullPointerException ne) {
      Log.w(TAG, ne.toString());
      promise.reject("No permission granted.", "Try again after adding permission.");
      return;
    }

    audioFileURL = (path.equals("DEFAULT")) ? FILE_LOCATION : path;
    _meteringEnabled = meteringEnabled;

    if (mediaRecorder == null) {
      mediaRecorder = new MediaRecorder();
    }

    if (audioSet != null) {
      mediaRecorder.setAudioSource(audioSet.hasKey("AudioSourceAndroid")
        ? audioSet.getInt("AudioSourceAndroid") : MediaRecorder.AudioSource.MIC);
      mediaRecorder.setOutputFormat(audioSet.hasKey("OutputFormatAndroid")
        ? audioSet.getInt("OutputFormatAndroid") : MediaRecorder.OutputFormat.MPEG_4);
      mediaRecorder.setAudioEncoder(audioSet.hasKey("AudioEncoderAndroid")
        ? audioSet.getInt("AudioEncoderAndroid") : MediaRecorder.AudioEncoder.AAC);
    } else {
      mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
    }

    mediaRecorder.setOutputFile(audioFileURL);

    try {
      mediaRecorder.prepare();
      mediaRecorder.start();
      final long systemTime = SystemClock.elapsedRealtime();
      this.recorderRunnable = new Runnable() {
        @Override
        public void run() {
          long time = SystemClock.elapsedRealtime() - systemTime;
          WritableMap obj = Arguments.createMap();
          obj.putDouble("current_position", time);
          if (_meteringEnabled) {
            int maxAmplitude = 0;
            if (mediaRecorder != null) {
              maxAmplitude = mediaRecorder.getMaxAmplitude();
            }
            double dB = -160;
            // 16-bit audio goes from -32768 to 32767
            double maxAudioSize = 32767;
            if (maxAmplitude > 0){
              dB = 20 * Math.log10(maxAmplitude / maxAudioSize);
            }
            Log.d(TAG, "update RECORDING DB: " + (int) dB);
            obj.putInt("current_metering", (int) dB);
          }
          sendEvent(reactContext, "rn-recordback", obj);
          recordHandler.postDelayed(this, subsDurationMillis);
        }
      };
      this.recorderRunnable.run();

      promise.resolve("file:///" + audioFileURL);
    } catch (Exception e) {
      Log.e(TAG, "Exception: ", e);
      promise.reject("startRecord", e.getMessage());
    }
  }

  @ReactMethod
  public void pauseRecorder(Promise promise) {
    if (mediaRecorder == null) {
      promise.reject("pauseRecord", "recorder is null.");
      return;
    }
    mediaRecorder.pause();
    promise.resolve("file:///" + audioFileURL);
  }

  @ReactMethod
  public void resumeRecorder(Promise promise) {
    if (mediaRecorder == null) {
      promise.reject("resumeRecord", "recorder is null.");
      return;
    }
    mediaRecorder.resume();
    promise.resolve("file:///" + audioFileURL);
  }

  @ReactMethod
  public void stopRecorder(Promise promise) {
    if (recordHandler != null) {
      recordHandler.removeCallbacks(this.recorderRunnable);
    }
    if (mediaRecorder == null) {
      promise.reject("stopRecord", "recorder is null.");
      return;
    }
    mediaRecorder.stop();
    mediaRecorder.release();
    mediaRecorder = null;
    promise.resolve("file:///" + audioFileURL);
  }

  @ReactMethod
  public void setVolume(double volume, Promise promise) {
    if (mediaPlayer == null) {
      promise.reject("setVolume", "player is null.");
      return;
    }
    float mVolume = (float) volume;
    mediaPlayer.setVolume(mVolume, mVolume);
    promise.resolve("set volume");
  }

  @ReactMethod
  public void startPlayer(final String path, final Boolean meteringEnabled, final Promise promise) {
    _meteringEnabled = meteringEnabled;
    if (mediaPlayer != null) {
      Boolean isPaused = !mediaPlayer.isPlaying() && mediaPlayer.getCurrentPosition() > 1;

      if (isPaused) {
        mediaPlayer.start();
        promise.resolve("player resumed.");
        return;
      }

      Log.e(TAG, "Player is already running. Stop it first.");
      promise.reject("startPlay", "Player is already running. Stop it first.");
      return;
    } else {
      mediaPlayer = new MediaPlayer();


      // Create the Visualizer object and attach it to our media player.
      mVisualizer = new Visualizer(mediaPlayer.getAudioSessionId());
      mVisualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
      mVisualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
      mVisualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);

      // Workaround for phone volume affecting waveform output
      // https://stackoverflow.com/questions/8048692/android-visualizer-fft-waveform-affected-by-device-volume
      Equalizer mEqualizer = new Equalizer(0, mediaPlayer.getAudioSessionId());
      mEqualizer.setEnabled(true); // need to enable equalizer

      // Pass through Visualizer data to VisualizerView
      // Visualizer.OnDataCaptureListener captureListener = new Visualizer.OnDataCaptureListener() {
      //   @Override
      //   public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
      //     // updateVisualizer(bytes);
      //   }

      //   @Override
      //   public void onFftDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
      //     // updateVisualizerFFT(bytes);
      //   }
      // };

      // mVisualizer.setDataCaptureListener(captureListener, Visualizer.getMaxCaptureRate() / 2, true, true);
      mVisualizer.setEnabled(true);
    }
    try {
      mediaPlayer.setDataSource(path.equals("DEFAULT") ? FILE_LOCATION : path);
      mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(final MediaPlayer mp) {
          mp.start();

          /**
           * Set timer task to send event to RN.
           */
          mTask = new TimerTask() {
            @Override
            public void run() {
              WritableMap obj = Arguments.createMap();
              int meteringdB = -160;
              if (mVisualizer.getEnabled()) {
                int success = mVisualizer.getMeasurementPeakRms(mMeasurementPeakRms);
                if (success == Visualizer.SUCCESS) {
                  double peakmB = mMeasurementPeakRms.mPeak;
                  // Peak is measured in millibels, convert to dB
                  meteringdB = (int) peakmB / 100;
                  Log.d(TAG, "update PLAYING METERING db: " + meteringdB);
                }
              }
              obj.putInt("duration", mp.getDuration());
              obj.putInt("current_position", mp.getCurrentPosition());
              obj.putInt("current_metering", meteringdB);
              sendEvent(reactContext, "rn-playback", obj);
            }
          };

          mTimer = new Timer();
          mTimer.schedule(mTask, 0, subsDurationMillis);

          String resolvedPath = (path.equals("DEFAULT")) ? "file:///" + FILE_LOCATION : path;
          promise.resolve(resolvedPath);
        }
      });
      /**
       * Detect when finish playing.
       */
      mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
          /**
           * Send last event
           */
          WritableMap obj = Arguments.createMap();
          obj.putInt("duration", mp.getDuration());
          obj.putInt("current_position", mp.getDuration());
          sendEvent(reactContext, "rn-playback", obj);

          /**
           * Reset player.
           */
          Log.d(TAG, "Plays completed. SETTING FALSE");
          mVisualizer.setEnabled(false);
          mTimer.cancel();
          mp.stop();
          mp.release();
          mediaPlayer = null;
        }
      });
      mediaPlayer.prepare();
    } catch (IOException e) {
      Log.e(TAG, "startPlay() io exception");
      promise.reject("startPlay", e.getMessage());
    } catch (NullPointerException e) {
      Log.e(TAG, "startPlay() null exception");
    }
  }

  @ReactMethod
  public void resumePlayer(Promise promise) {
    if (mediaPlayer == null) {
      promise.reject("resume","mediaPlayer is null.");
      return;
    }

    if (mediaPlayer.isPlaying()) {
      promise.reject("resume","mediaPlayer is already running.");
      return;
    }

    try {
      mediaPlayer.seekTo(mediaPlayer.getCurrentPosition());
      mediaPlayer.start();
      promise.resolve("resume player");
    } catch (Exception e) {
      Log.e(TAG, "mediaPlayer resume: " + e.getMessage());
      promise.reject("resume", e.getMessage());
    }
  }

  @ReactMethod
  public void pausePlayer(Promise promise) {
    if (mediaPlayer == null) {
      promise.reject("pausePlay","mediaPlayer is null.");
      return;
    }

    try {
      mediaPlayer.pause();
      promise.resolve("pause player");
    } catch (Exception e) {
      Log.e(TAG, "pausePlay exception: " + e.getMessage());
      promise.reject("pausePlay",e.getMessage());
    }
  }

  @ReactMethod
  public void seekToPlayer(int time, Promise promise) {
    if (mediaPlayer == null) {
      promise.reject("seekTo","mediaPlayer is null.");
      return;
    }

    int millis = time * 1000;

    mediaPlayer.seekTo(millis);
    promise.resolve("pause player");
  }

  private void sendEvent(ReactContext reactContext,
                         String eventName,
                         @Nullable WritableMap params) {
    reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit(eventName, params);
  }

  @ReactMethod
  public void stopPlayer(Promise promise) {
    if (mTimer != null) {
      mTimer.cancel();
    }

    if (mediaPlayer == null) {
      promise.reject("stopPlay","mediaPlayer is null.");
      return;
    }

    try {
      mediaPlayer.release();
      mediaPlayer = null;
      promise.resolve("stopped player");
    } catch (Exception e) {
      Log.e(TAG, "stopPlay exception: " + e.getMessage());
      promise.reject("stopPlay",e.getMessage());
    }
  }

  @ReactMethod
  public void setSubscriptionDuration(double sec, Promise promise) {
    this.subsDurationMillis = (int) (sec * 1000);
    promise.resolve("setSubscriptionDuration: " + this.subsDurationMillis);
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    switch (requestCode) {
      case REQUEST_RECORD_AUDIO_PERMISSION:
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
          return true;
        break;
    }
    return false;
  }

  public void updateVisualizer(byte[] bytes) {
    int t = calculateRMSLevel(bytes);
    // if (mVisualizer.getEnabled()) {
    //   int success = mVisualizer.getMeasurementPeakRms(mMeasurementPeakRms);
    //   int rmsMb = mMeasurementPeakRms.mRms;
    //   int rmsDb = (int) rmsMb / 100;
    // }
    mBytes = bytes;
  }

  /**
   * Pass FFT data to the visualizer. Typically this will be obtained from the
   * Android Visualizer.OnDataCaptureListener call back. See
   * {@link android.media.audiofx.Visualizer.OnDataCaptureListener#onFftDataCapture }
   * 
   * @param bytes
   */
  public void updateVisualizerFFT(byte[] bytes) {
    int t = calculateRMSLevel(bytes);
    Log.d(TAG, "update FFT: " + t);
    mFFTBytes = bytes;
  }

  public int calculatePeak(byte[] audioData) {
    double max = 0;
    for (int i = 0; i < audioData.length / 2; i++) {
      double curAmp = (audioData[i] | audioData[i + 1] << 8) / 32768.0;
      if (curAmp > max) max = curAmp;
    }
    return (int) max;
  }

  public int calculateRMSLevel(byte[] audioData) {
    // System.out.println("::::: audioData :::::"+audioData);
    // double amplitude = 0;
    // for (int i = 0; i < audioData.length; i++) {
    //   amplitude += Math.abs((double) (audioData[i] / 32768.0));
    // }
    // amplitude = amplitude / audioData.length;


    double amplitude = 0;
    if (audioData == null) {
      Log.d(TAG, "NULL AUDIO DATA -- RETURN NULL");
      return (int) amplitude;
    }

    for (int i = 0; i < audioData.length; i++) {
      double y = (audioData[i] | audioData[i+1] << 8) / 32768.0;
      // depending on your endianness:
      // double y = (audioData[i*2]<<8 | audioData[i*2+1]) / 32768.0
      amplitude += Math.abs(y);
     }
    amplitude = amplitude / (audioData.length / 2);


    // Add this data to buffer for display
    if (counterPlayer < 100) {
      drawingBufferForPlayer[counterPlayer++] = amplitude;
    } else {
      for (int k = 0; k < 99; k++) {
        drawingBufferForPlayer[k] = drawingBufferForPlayer[k + 1];
      }
      drawingBufferForPlayer[99] = amplitude;
    }

    // updateBufferDataPlayer(drawingBufferForPlayer);
    // setDataForPlayer(100, 100);
    Log.d(TAG, "AMPLITUDE: " + amplitude);
    return (int) amplitude;
  }
}
