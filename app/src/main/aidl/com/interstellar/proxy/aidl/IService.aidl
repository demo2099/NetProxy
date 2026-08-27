package com.interstellar.proxy.aidl;

import com.interstellar.proxy.aidl.IServiceCallback;

interface IService {
    int getStatus();
    void registerCallback(in IServiceCallback callback);
    oneway void unregisterCallback(in IServiceCallback callback);
}
