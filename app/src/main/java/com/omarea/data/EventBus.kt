package com.omarea.data

import android.util.Log
import java.util.*

object EventBus {
    private val eventReceivers = ArrayList<IEventReceiver>()

    /**
     * Publish event
     *
     * @param eventType event type
     */
    fun publish(eventType: EventType?, data: HashMap<String, Any>? = null) {
        if (eventReceivers.size > 0) {
            // iterate over a copy to avoid a crash if eventReceivers changes during unsubscribe
            val temp = ArrayList(eventReceivers)
            for (eventReceiver in temp) {
                try {
                    if (eventReceiver.eventFilter(eventType!!)) {
                        if (eventReceiver.isAsync) {
                            HandlerThread(eventReceiver, eventType, data).start()
                        } else {
                            eventReceiver.onReceive(eventType, data)
                        }
                    }
                } catch (ex: Exception) {
                    Log.e("SceneEventBus", "" + ex.message)
                }
            }
        }
    }

    /**
     * Subscribe to event
     *
     * @param eventReceiver event receiver
     */
    fun subscribe(eventReceiver: IEventReceiver) {
        if (!eventReceivers.contains(eventReceiver)) {
            eventReceivers.add(eventReceiver)
            eventReceiver.onSubscribe()
        }
    }

    /**
     * Unsubscribe from event
     *
     * @param eventReceiver event receiver
     */
    fun unsubscribe(eventReceiver: IEventReceiver) {
        if (eventReceivers.contains(eventReceiver)) {
            eventReceivers.remove(eventReceiver)
            eventReceiver.onUnsubscribe()
        }
    }

    internal class HandlerThread(private val eventReceiver: IEventReceiver, private val eventType: EventType?, private val data: HashMap<String, Any>?) : Thread() {
        override fun run() {
            eventReceiver.onReceive(eventType!!, data)
        }
    }
}