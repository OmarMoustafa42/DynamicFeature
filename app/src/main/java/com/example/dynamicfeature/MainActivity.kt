package com.example.dynamicfeature

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus


class MainActivity : AppCompatActivity() {

    private var mySessionID = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnOnInstall = findViewById<Button>(R.id.on_install)
        val btnOnDemand = findViewById<Button>(R.id.on_demand)

        btnOnInstall.setOnClickListener {
            val intent = Intent().setClassName(BuildConfig.APPLICATION_ID, "com.example.oninstall.MainActivity")
            startActivity(intent)
        }

        val layout: ConstraintLayout = findViewById(R.id.display)
        val progressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleLarge)
        val params = ConstraintLayout.LayoutParams(100, 100)
        params.topToTop = layout.id
        params.bottomToBottom = layout.id
        params.rightToRight = layout.id
        params.leftToLeft = layout.id
        params.topMargin = 1500
        layout.addView(progressBar, params)
        progressBar.visibility = View.INVISIBLE

//        val progressBar = findViewById<ProgressBar>(R.id.onDemandProgressBar)
        val progressBarLabel = findViewById<TextView>(R.id.progressBarLabel)
        val splitInstallManager = SplitInstallManagerFactory.create(this)
        btnOnDemand.setOnClickListener {
            startDownloading(splitInstallManager, progressBar, progressBarLabel)
        }
    }

    private fun startDownloading(
        splitInstallManager: SplitInstallManager,
        progressBar: ProgressBar,
        progressBarLabel: TextView,
    ) {
        val request = SplitInstallRequest.newBuilder().addModule("OnDemand").build()

        val tag = "DOWNLOAD STATUS"
        val listener =
            SplitInstallStateUpdatedListener { SplitInstallSessionState ->
                if (SplitInstallSessionState.sessionId() == mySessionID) {
                    when (SplitInstallSessionState.status()) {
                        SplitInstallSessionStatus.DOWNLOADING -> {
                            // Download Started
                            progressBarLabel.text = resources.getText(R.string.downloading)
                            progressBar.visibility = View.VISIBLE
                            Log.i(tag, "DOWNLOADING")
                        }
                        SplitInstallSessionStatus.INSTALLED -> {
                            // Module installed successfully
                            val intent = Intent().setClassName(BuildConfig.APPLICATION_ID, "com.example.ondemand.MainActivity")
                            Log.i(tag, "INSTALLED")
                            startActivity(intent)
                        }
                        SplitInstallSessionStatus.CANCELED -> {
                            progressBarLabel.text = resources.getText(R.string.unclicked)
                            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
                            Log.i(tag, "CANCELED")
                        }
                        SplitInstallSessionStatus.CANCELING -> {
                            progressBarLabel.text = resources.getText(R.string.cancelling)
                            progressBar.visibility = View.VISIBLE
                            Log.i(tag, "CANCELING")
                        }
                        SplitInstallSessionStatus.DOWNLOADED -> {
                            progressBarLabel.text = resources.getText(R.string.downloaded)
                            progressBar.visibility = View.VISIBLE
                            Log.i(tag, "DOWNLOADED")
                        }
                        SplitInstallSessionStatus.FAILED -> {
                            progressBarLabel.text = resources.getText(R.string.failed)
                            progressBar.visibility = View.VISIBLE
                            Log.i(tag, "FAILED")
                        }
                        SplitInstallSessionStatus.INSTALLING -> {
                            progressBarLabel.text = resources.getText(R.string.installing)
                            progressBar.visibility = View.VISIBLE
                            Log.i(tag, "INSTALLING")
                        }
                        SplitInstallSessionStatus.PENDING -> {
                            progressBarLabel.text = resources.getText(R.string.pending)
                            progressBar.visibility = View.VISIBLE
                            Log.i(tag, "PENDING")
                        }
                        SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> {
                            Log.i(tag, "REQUIRES USER CONFIRMATION")
                        }
                        SplitInstallSessionStatus.UNKNOWN -> {
                            Log.i(tag, "UNKNOWN")
                        }
                    }
                }
            }

        splitInstallManager.registerListener(listener)

        splitInstallManager.startInstall(request)
            .addOnFailureListener { e ->
                Log.e("ERROR", "Failed to start installing Module $e")
            }
            .addOnSuccessListener { sessionID ->
                mySessionID = sessionID
            }
    }
}