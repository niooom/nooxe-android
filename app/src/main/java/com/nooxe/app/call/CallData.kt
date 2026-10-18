package com.nooxe.app.call

import org.json.JSONObject

enum class CallPhase { IDLE, RINGING, CONNECTING, ACTIVE, BUSY }

/** Everything one call needs: who it is with, and how to join the room. */
data class CallData(
    val callId: String,
    val roomName: String,
    val url: String,
    val token: String,
    val callType: String,
    val peerName: String,
    val peerAvatar: String,
    val actionUrl: String
) {
    fun isVideo(): Boolean = callType == "video"

    fun toJson(): String {
        val json = JSONObject()
        json.put("call_id", callId)
        json.put("room_name", roomName)
        json.put("url", url)
        json.put("token", token)
        json.put("call_type", callType)
        json.put("peer_name", peerName)
        json.put("peer_avatar", peerAvatar)
        json.put("action_url", actionUrl)
        return json.toString()
    }

    companion object {

        fun fromJson(json: String?): CallData? {
            if (json == null || json.isEmpty()) return null
            return try {
                val source = JSONObject(json)
                val callId = source.optString("call_id", "")
                val url = source.optString("url", "")
                val token = source.optString("token", "")
                if (callId.isEmpty() || url.isEmpty() || token.isEmpty()) {
                    null
                } else {
                    CallData(
                        callId = callId,
                        roomName = source.optString("room_name", callId),
                        url = url,
                        token = token,
                        callType = source.optString("call_type", "audio"),
                        peerName = source.optString("peer_name", ""),
                        peerAvatar = source.optString("peer_avatar", ""),
                        actionUrl = source.optString("action_url", "")
                    )
                }
            } catch (error: Exception) {
                null
            }
        }

        fun fromPush(push: Map<String, String>?): CallData? {
            if (push == null) return null
            val json = JSONObject()
            for ((key, value) in push) {
                json.put(key, value)
            }
            return fromJson(json.toString())
        }
    }
}
