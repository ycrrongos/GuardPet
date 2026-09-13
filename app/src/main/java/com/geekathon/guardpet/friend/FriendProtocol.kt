package com.geekathon.guardpet.friend

import org.json.JSONObject

data class FriendPetSnapshot(
    val mood: Int = 70,
    val hunger: Int = 60,
    val food: Int = 0,
    val level: Int = 0,
    val xp: Int = 0,
    val state: String = "idle",
    val styleHint: Int = 0,
    val avatarHash: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mood", mood)
        .put("hunger", hunger)
        .put("food", food)
        .put("level", level)
        .put("xp", xp)
        .put("state", state)
        .put("styleHint", styleHint)
        .put("avatarHash", avatarHash)

    companion object {
        fun fromJson(raw: JSONObject?): FriendPetSnapshot {
            if (raw == null) return FriendPetSnapshot()
            return FriendPetSnapshot(
                mood = raw.optInt("mood", 70).coerceIn(0, 100),
                hunger = raw.optInt("hunger", 60).coerceIn(0, 100),
                food = raw.optInt("food", 0).coerceAtLeast(0),
                level = raw.optInt("level", 0).coerceAtLeast(0),
                xp = raw.optInt("xp", 0).coerceAtLeast(0),
                state = raw.optString("state", "idle").ifBlank { "idle" },
                styleHint = raw.optInt("styleHint", 0),
                avatarHash = raw.optString("avatarHash", "")
            )
        }
    }
}

data class FriendMember(
    val userId: String,
    val name: String,
    val pet: FriendPetSnapshot
)

object FriendProtocol {
    fun hello(userId: String, name: String, room: String): String =
        JSONObject()
            .put("type", "hello")
            .put("userId", userId)
            .put("name", name)
            .put("room", room)
            .toString() + "\n"

    fun state(pet: FriendPetSnapshot): String =
        JSONObject()
            .put("type", "state")
            .put("pet", pet.toJson())
            .toString() + "\n"

    fun avatar(userId: String, hash: String, b64: String): String =
        JSONObject()
            .put("type", "avatar")
            .put("userId", userId)
            .put("hash", hash)
            .put("mime", "image/png")
            .put("data", b64)
            .toString() + "\n"

    fun avatarNeed(userId: String, hash: String): String =
        JSONObject()
            .put("type", "avatar_need")
            .put("userId", userId)
            .put("hash", hash)
            .toString() + "\n"

    fun ping(): String = """{"type":"ping"}""" + "\n"

    fun parseMembers(arr: org.json.JSONArray?): List<FriendMember> {
        if (arr == null) return emptyList()
        val out = ArrayList<FriendMember>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += FriendMember(
                userId = o.optString("userId"),
                name = o.optString("name", "好友"),
                pet = FriendPetSnapshot.fromJson(o.optJSONObject("pet"))
            )
        }
        return out
    }
}
