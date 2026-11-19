package com.sw.driveeye.linkdevice;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;

public class XMLinkDevice {
    public IXMDeviceEventCallback deviceEventCallback;

    public interface ICanUpdateDevice {
        void canUpdate(boolean canUpdate);
    }

    public interface IXMDeviceEventCallback {
        void onAlarmRsg(List list);

        void onAlarmRsgAdas(List list);

        boolean onAudioData(byte[] data, long pts);

        void onAudioHeader(int a, int b, int c);

        void onDisconnectEvent();

        void onDriveCar();

        void onFirstYUVFrame(int width, int height);

        void onFlashEncryption(int status);

        void onReturnCar();

        void yuvCallBack(ByteBuffer byteBuffer, int width, int height, long timestamp);
    }

    @FunctionalInterface
    public interface IXMGetDeviceInfoCallback {
        void onDeviceConfigResultCallback(HashMap map);
    }

    private native void init();

    public native boolean captureJpgImage(String filePath);

    public native synchronized void closeVideo();

    public native void getAdasInfo(IXMGetDeviceInfoCallback callback);

    public native void getBitsPerSecond(IXMGetDeviceInfoCallback callback);

    public native void getCalibrationSetting(IXMGetDeviceInfoCallback callback);

    public native void getDetectionModel(IXMGetDeviceInfoCallback callback);

    public native void getDeviceAbilities(IXMGetDeviceInfoCallback callback);

    public native void getDeviceInfoV2(IXMGetDeviceInfoCallback callback);

    public native void getGraphic(IXMGetDeviceInfoCallback callback);

    public native void getIOSwitch(IXMGetDeviceInfoCallback callback);

    public native void getMirror(IXMGetDeviceInfoCallback callback);

    public native int getRecordMp4Duration();

    public native void getRuler(IXMGetDeviceInfoCallback callback);

    public native void getSystemDisposition(IXMGetDeviceInfoCallback callback);

    public native double getUpdateProgress();

    public native boolean importConfiguration(String configJson);

    public native void initSDK();

    public native boolean modifyDeviceWifiPassword(String password);

    public native synchronized void preparePlay(int width, int height);

    public native void releaseSDK();

    public native boolean restoreFactorySetting();

    public native boolean setAdasSwitch(int value);

    public native boolean setBitsPerSecond(int value);

    public native boolean setDetectionModel(int value);

    public native boolean setDeviceInfoV2(String json);

    public native boolean setEarlyWarningSwitch(int value);

    public native boolean setGraphic(double luminance, double chroma, double contrast, double saturation);

    public native boolean setIOSwitch(int value);

    public native boolean setLensInstallInfo(int angle, double distance);

    public native boolean setMirror(int mirrorMode);

    public native boolean setRuler(double[] xPoints, double[] yPoints, int count, String rulerType);

    public native boolean setRulerSwitch(int enable);

    public native boolean setSystemDisposition(
            double[] xPoints,
            double[] yPoints,
            int count,
            String rulerType,
            int earlyWarningSwitch,
            int rulerSwitch,
            int mirror,
            int detectionModel,
            double luminance,
            double chroma,
            double contrast,
            double saturation,
            int adasSwitch,
            int angle,
            double distance
    );

    public native synchronized boolean startConnect();

    public native boolean startSaveVideo(String filePath);

    public native boolean stopSaveVideo();

    public native boolean updateDevice(String filePath, ICanUpdateDevice callback);

    static {
        System.loadLibrary("xmlinkdevice");
    }

    public XMLinkDevice(IXMDeviceEventCallback callback) {
        this.deviceEventCallback = callback;
        init();
    }
}
