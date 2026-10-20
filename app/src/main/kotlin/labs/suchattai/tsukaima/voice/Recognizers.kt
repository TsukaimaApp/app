package labs.suchattai.tsukaima.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService

/**
 * Installed recognition services other than Tsukaima's own (`assist.TsukaimaRecognitionService`), so neither voice
 * mode nor the forwarding recogniser can ever pick this package.
 */
internal fun recognizers(context: Context): List<ServiceInfo> =
    context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .map { it.serviceInfo }
        .filter { it.packageName != context.packageName }

/**
 * The recogniser to use instead of Tsukaima's own: a system one only (preinstalled or its update), never an arbitrary
 * third-party app the user did not pick for the microphone. Null when there is none.
 */
internal fun systemRecognizer(context: Context): ComponentName? = recognizers(context)
    .firstOrNull { (it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 }
    ?.let { ComponentName(it.packageName, it.name) }
