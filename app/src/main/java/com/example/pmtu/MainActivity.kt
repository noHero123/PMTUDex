package com.example.pmtu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import me.dm7.barcodescanner.zxing.ZXingScannerView
import com.google.zxing.Result

class MainActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {
    private var mScannerView: ZXingScannerView? = null
    private val CAMERA_PERMISSION_CODE = 100
    private val FRONT_CAMERA_ID = 1
    private val BACK_CAMERA_ID = 0
    private var mCurrentCameraId = FRONT_CAMERA_ID

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Programmatically initialize the scanner view
        mScannerView = ZXingScannerView(this)
        // Disable the red laser line
        mScannerView?.setLaserEnabled(false)
        
        rootLayout.addView(mScannerView)

        // Add a button to switch cameras
        val switchButton = Button(this)
        switchButton.text = "Switch Camera"
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        switchButton.layoutParams = params
        
        // Adjust button position to be below the camera notch/status bar
        ViewCompat.setOnApplyWindowInsetsListener(switchButton) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = insets.top + 16 // Add some extra padding
            }
            windowInsets
        }

        switchButton.setOnClickListener {
            switchCamera()
        }
        rootLayout.addView(switchButton)

        // Set the root layout as the content view
        setContentView(rootLayout)

        checkPermission(Manifest.permission.CAMERA, CAMERA_PERMISSION_CODE)
    }

    private fun switchCamera() {
        mScannerView?.stopCamera()
        mCurrentCameraId = if (mCurrentCameraId == FRONT_CAMERA_ID) BACK_CAMERA_ID else FRONT_CAMERA_ID
        mScannerView?.startCamera(mCurrentCameraId)
    }

    private fun checkPermission(permission: String, requestCode: Int) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mScannerView?.startCamera(mCurrentCameraId)
            } else {
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mScannerView?.setResultHandler(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            mScannerView?.startCamera(mCurrentCameraId)
        }
    }

    override fun onPause() {
        super.onPause()
        mScannerView?.stopCamera()
    }

    override fun handleResult(rawResult: Result) {
        Log.v("result", rawResult.text)
        
        // Close the scanner and open ResultActivity
        val intent = Intent(this, ResultActivity::class.java)
        intent.putExtra("SCANNED_TEXT", rawResult.text)
        startActivity(intent)
        
        // Optional: finish the current activity if you don't want to go back to scanner
        // finish()
    }
}
