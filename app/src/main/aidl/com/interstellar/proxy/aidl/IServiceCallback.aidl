package com.interstellar.proxy.aidl;

interface IServiceCallback {
    void onServiceStatusChanged(int status);
    void onServiceAlert(int type, String message);
}
