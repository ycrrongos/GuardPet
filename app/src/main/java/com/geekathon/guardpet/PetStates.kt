package com.geekathon.guardpet

enum class PetState(val key: String, val label: String) {
    IDLE("idle", "idle / 待机"),
    TOUCH("touch", "touch / 点击"),
    FEED("feed", "feed / 喂食"),
    PET("pet", "pet / 抚摸"),
    WALK("walk", "walk / 行走"),
    PLAY("play", "play / 玩耍"),
    RANDOM("random", "random / 随机"),
    BORED("bored", "bored / 发呆"),
    AIR("air", "air / 空中"),
    SLEEP("sleep", "sleep / 睡觉"),
    HAPPY("happy", "happy / 开心"),
    SAD("sad", "sad / 难过"),
    ANGRY("angry", "angry / 生气"),
    HIDDEN("hidden", "hidden / 隐藏");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: IDLE
    }
}
