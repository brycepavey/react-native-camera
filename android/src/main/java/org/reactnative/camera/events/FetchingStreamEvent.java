package org.reactnative.camera.events;

import android.support.v4.util.Pools;

import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.reactnative.camera.CameraViewManager;

public class FetchingStreamEvent extends Event<FetchingStreamEvent> {
    private static final Pools.SynchronizedPool<FetchingStreamEvent> EVENTS_POOL = new Pools.SynchronizedPool<>(5);
    private FetchingStreamEvent() {}


    public static FetchingStreamEvent obtain(int viewTag) {
        FetchingStreamEvent event = EVENTS_POOL.acquire();
        if (event == null) {
            event = new FetchingStreamEvent();
        }
        event.init(viewTag, new WritableNativeMap());
        return event;
    }

    private void init(int viewTag, WritableMap response) {
        super.init(viewTag);
    }

    @Override
    public short getCoalescingKey() {
        int hashCode = 200;
        return (short) hashCode;
    }

    @Override
    public String getEventName() {
        return CameraViewManager.Events.EVENT_ON_FETCHING_STREAM.toString();
    }

    @Override
    public void dispatch(RCTEventEmitter rctEventEmitter) {
        rctEventEmitter.receiveEvent(getViewTag(), getEventName(), new WritableNativeMap());
    }
}
