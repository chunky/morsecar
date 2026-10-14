package org.icculus.chunky.morsecar

import android.app.Application

class MorseCarApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
