package com.example.pmtu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import me.dm7.barcodescanner.zxing.ZXingScannerView
import com.google.zxing.Result

class MainActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {
    private var mScannerView: ZXingScannerView? = null
    private val CAMERA_PERMISSION_CODE = 100
    private val FRONT_CAMERA_ID = 1

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Programmatically initialize the scanner view
        mScannerView = ZXingScannerView(this)
        // Set the scanner view as the content view
        setContentView(mScannerView)

        checkPermission(Manifest.permission.CAMERA, CAMERA_PERMISSION_CODE)
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
                mScannerView?.startCamera(FRONT_CAMERA_ID)
            } else {
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mScannerView?.setResultHandler(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            mScannerView?.startCamera(FRONT_CAMERA_ID)
        }
    }

    override fun onPause() {
        super.onPause()
        mScannerView?.stopCamera()
    }

    override fun handleResult(rawResult: Result) {
        Log.v("result", rawResult.text)
        Log.v("result", rawResult.barcodeFormat.toString())

        // Provide feedback to the user
        Toast.makeText(this, "Scanned: ${rawResult.text}", Toast.LENGTH_SHORT).show()

        // If you need to return the result to a calling activity, you can do it here,
        // but note that calling finish() would close the scanner.
        val intent = Intent()
        intent.putExtra("KEY_QR_CODE", rawResult.text)
        setResult(RESULT_OK, intent)

        // Resume camera preview to allow further scanning
        mScannerView?.resumeCameraPreview(this)
    }
}
