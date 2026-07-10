package jp.asystem.taxitranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import jp.asystem.taxitranslator.model.AppMode
import jp.asystem.taxitranslator.ui.TranslatorScreen

class MainActivity : ComponentActivity() {

    private val viewModel: TranslatorViewModel by viewModels()

    /** 2台モード選択時、Nearby権限の許可待ちのモード */
    private var pendingMode: AppMode? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.start() else viewModel.onPermissionDenied()
        }

    private val nearbyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val mode = pendingMode
            pendingMode = null
            if (mode != null) {
                if (grants.values.all { it }) viewModel.setMode(mode)
                else viewModel.onNearbyPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 車載据え置きのため画面は常時ON
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by viewModel.ui.collectAsState()
                TranslatorScreen(
                    ui = ui,
                    onModeSelect = ::onModeSelect,
                    onChangeMode = viewModel::changeMode,
                    onDirectionChange = viewModel::setDirection,
                    onTargetChange = viewModel::setTarget,
                    onToggleTts = viewModel::toggleTts,
                    onSetOffline = viewModel::setForcedOffline,
                    onReset = viewModel::reset,
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onModeSelect(mode: AppMode) {
        if (mode == AppMode.SOLO) {
            viewModel.setMode(mode)
            return
        }
        val perms = nearbyPermissions()
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.setMode(mode)
        } else {
            pendingMode = mode
            nearbyPermissionLauncher.launch(perms)
        }
    }

    /** Nearby Connectionsに必要なランタイム権限(OSバージョンで異なる)。 */
    private fun nearbyPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
