package com.blazingjoker.blazingjokergame

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class GameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.immersive(this)
        gameView = GameView(this)
        gameView.onExit = { finish() }
        setContentView(gameView)
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
        gameView.resumeGameLoop()
    }

    override fun onPause() {
        super.onPause()
        gameView.pauseGame()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        gameView.pauseGame()
    }
}
