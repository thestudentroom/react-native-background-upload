package com.vydia.RNUploader;

import android.content.Context;
import android.support.annotation.Nullable;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import net.gotev.uploadservice.BinaryUploadRequest;
import net.gotev.uploadservice.HttpUploadRequest;
import net.gotev.uploadservice.MultipartUploadRequest;
import net.gotev.uploadservice.ServerResponse;
import net.gotev.uploadservice.UploadInfo;
import net.gotev.uploadservice.UploadNotificationConfig;
import net.gotev.uploadservice.UploadService;
import net.gotev.uploadservice.UploadStatusDelegate;
import net.gotev.uploadservice.okhttp.OkHttpStack;

import java.io.File;

/**
 * Created by stephen on 12/8/16.
 */
public class UploaderModule extends ReactContextBaseJavaModule {
  private static final String TAG = "UploaderBridge";

  public UploaderModule(ReactApplicationContext reactContext) {
    super(reactContext);
    UploadService.NAMESPACE = reactContext.getApplicationInfo().packageName;
    UploadService.HTTP_STACK = new OkHttpStack();
  }

  @Override
  public String getName() {
    return "RNFileUploader";
  }

  /*
  Sends an event to the JS module.
   */
  private void sendEvent(String eventName, @Nullable WritableMap params) {
    this.getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("RNFileUploader-" + eventName, params);
  }

  /*
  Gets file information for the path specified.  Example valid path is: /storage/extSdCard/DCIM/Camera/20161116_074726.mp4
  Returns an object such as: {extension: "mp4", size: "3804316", exists: true, mimeType: "video/mp4", name: "20161116_074726.mp4"}
   */
  @ReactMethod
  public void getFileInfo(String path, final Promise promise) {
    try {
      WritableMap params = Arguments.createMap();
      File fileInfo = new File(path);
      params.putString("name", fileInfo.getName());
      if (!fileInfo.exists() || !fileInfo.isFile())
      {
        params.putBoolean("exists", false);
      }
      else
      {
        params.putBoolean("exists", true);
        params.putString("size",Long.toString(fileInfo.length())); //use string form of long because there is no putLong and converting to int results in a max size of 17.2 gb, which could happen.  Javascript will need to convert it to a number
        String extension = MimeTypeMap.getFileExtensionFromUrl(path);
        params.putString("extension",extension);
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        params.putString("mimeType", mimeType);
      }

      promise.resolve(params);
    } catch (Exception exc) {
      Log.e(TAG, exc.getMessage(), exc);
      promise.reject(exc);
    }
  }

  /*
  * Starts a file upload.
  * Options are passed in as the first argument as a js hash:
  * {
  *   url: string.  url to post to.
  *   path: string.  path to the file on the device
  *   headers: hash of name/value header pairs
  *   method: HTTP method to use.  Default is "POST"
  *   notification: hash for customizing tray notifiaction
  *     enabled: boolean to enable/disabled notifications, true by default.
  * }
  *
  * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  public void startUpload(ReadableMap options, final Promise promise) {
    for (String key : new String[]{"url", "path"}) {
      if (!options.hasKey(key)) {
        promise.reject(new IllegalArgumentException("Missing '" + key + "' field."));
        return;
      }
      if (options.getType(key) != ReadableType.String) {
        promise.reject(new IllegalArgumentException(key + " must be a string."));
        return;
      }
    }
    if (options.hasKey("headers") && options.getType("headers") != ReadableType.Map) {
      promise.reject(new IllegalArgumentException("headers must be a hash."));
      return;
    }
    if (options.hasKey("notification") && options.getType("notification") != ReadableType.Map) {
      promise.reject(new IllegalArgumentException("notification must be a hash."));
      return;
    }
    
    tring requestType = "raw";
    if (options.hasKey("type")) {
      requestType = options.getString("type");
      if (requestType == null) {
        promise.reject(new IllegalArgumentException("type must be string."));
        return;
      }

      if (!requestType.equals("raw") && !requestType.equals("multipart")) {
        promise.reject(new IllegalArgumentException("type should be string: raw or multipart."));
        return;
      }
    }

    WritableMap notification = new WritableNativeMap();
    notification.putBoolean("enabled", true);
    if (options.hasKey("notification")) {
      notification.merge(options.getMap("notification"));
    }

    String url = options.getString("url");
    String filePath = options.getString("path");
    String method = options.hasKey("method") && options.getType("method") == ReadableType.String ? options.getString("method") : "POST";
    final String customUploadId = options.hasKey("customUploadId") && options.getType("method") == ReadableType.String ? options.getString("customUploadId") : null;
    
    UploadStatusDelegate statusDelegate = new UploadStatusDelegate() {
        @Override
        public void onProgress(Context context, UploadInfo uploadInfo) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          params.putInt("progress", uploadInfo.getProgressPercent()); //0-100
          sendEvent("progress", params);
        }

        @Override
        public void onError(Context context, UploadInfo uploadInfo, Exception exception) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          params.putString("error", exception.getMessage());
          sendEvent("error", params);
        }

        @Override
        public void onCompleted(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          params.putInt("responseCode", serverResponse.getHttpCode());
          params.putString("responseBody", serverResponse.getBodyAsString());
          sendEvent("completed", params);
        }

        @Override
        public void onCancelled(Context context, UploadInfo uploadInfo) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          sendEvent("cancelled", params);
        }
      };

      HttpUploadRequest<?> request;

      if (requestType.equals("raw")) {
       if (customUploadId != null) {
         request = new BinaryUploadRequest(this.getReactApplicationContext(), customUploadId, url)
                 .setFileToUpload(filePath);
       } else {
         request = new BinaryUploadRequest(this.getReactApplicationContext(), url)
                 .setFileToUpload(filePath);
       }
      } else {
       if (!options.hasKey("field")) {
         promise.reject(new IllegalArgumentException("field is required field for multipart type."));
         return;
       }

       if (options.getType("field") != ReadableType.String) {
         promise.reject(new IllegalArgumentException("field must be string."));
         return;
       }

       if (customUploadId != null) {
         request = new MultipartUploadRequest(this.getReactApplicationContext(), customUploadId, url)
                 .addFileToUpload(filePath, options.getString("field"));
       } else {
         request = new MultipartUploadRequest(this.getReactApplicationContext(), url)
                 .addFileToUpload(filePath, options.getString("field"));
       }
      }

      request.setMethod(method)
        .setMaxRetries(2)
        .setDelegate(statusDelegate);
    
      if (notification.getBoolean("enabled")) {
        request.setNotificationConfig(new UploadNotificationConfig());
      }
      if (options.hasKey("headers")) {
        ReadableMap headers = options.getMap("headers");
        ReadableMapKeySetIterator keys = headers.keySetIterator();
        while (keys.hasNextKey()) {
          String key = keys.nextKey();
          if (headers.getType(key) != ReadableType.String) {
            promise.reject(new IllegalArgumentException("Headers must be string key/values.  Value was invalid for '" + key + "'"));
            return;
          }
          request.addHeader(key, headers.getString(key));
        }
      }
      String uploadId = request.startUpload();
      promise.resolve(customUploadId != null ? customUploadId : uploadId);
    } catch (Exception exc) {
      Log.e(TAG, exc.getMessage(), exc);
      promise.reject(exc);
    }
  }

}
