package org.reactnative.camera.events;

import android.support.v4.util.Pools;

import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.reactnative.camera.CameraViewManager;

public class StreamReceivedEvent extends Event<StreamReceivedEvent> {
    private static final Pools.SynchronizedPool<StreamReceivedEvent> EVENTS_POOL = new Pools.SynchronizedPool<>(5);
    private StreamReceivedEvent() {}

    private WritableMap mResponse;

    public static StreamReceivedEvent obtain(int viewTag, WritableMap response) {
        StreamReceivedEvent event = EVENTS_POOL.acquire();
        if (event == null) {
            event = new StreamReceivedEvent();
        }
        event.init(viewTag, response);
        return event;
    }

    private void init(int viewTag, WritableMap response) {
        super.init(viewTag);
        mResponse = response;
    }

    @Override
    public short getCoalescingKey() {
        int hashCode = 100;
        return (short) hashCode;
    }

    @Override
    public String getEventName() {
        return CameraViewManager.Events.EVENT_ON_RECEIVE_STREAM.toString();
    }

    @Override
    public void dispatch(RCTEventEmitter rctEventEmitter) {
        rctEventEmitter.receiveEvent(getViewTag(), getEventName(), mResponse);
    }
}
