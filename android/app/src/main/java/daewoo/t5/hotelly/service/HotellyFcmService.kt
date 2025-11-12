package daewoo.t5.hotelly.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import daewoo.t5.hotelly.MainActivity
import daewoo.t5.hotelly.R
import daewoo.t5.hotelly.utils.Constant


class HotellyFcmService : FirebaseMessagingService() {

    private val CHANNEL_ID = "hotelly_channel_01"

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.e("HotellyFCM", "Message received: ${message.data}")

        // 우선 데이터 페이로드 우선으로 파싱, 없으면 notification 필드 사용
        val title = message.data["title"] ?: message.notification?.title ?: "Hotelly"
        val body = message.data["body"] ?: message.notification?.body ?: "새 알림이 도착했습니다."
        val clickActionPath =
            message.data["click_action"] ?: message.notification?.clickAction ?: "/"

        showNotification(title, body, clickActionPath)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("HotellyFCM", "New FCM Token: $token")
        // 필요하면 토큰을 서버에 전송하는 로직 추가
    }

    private fun showNotification(title: String, body: String, clickAction: String) {
        createNotificationChannelIfNeeded()

        // 앱을 열 때 이동할 인텐트
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Constant.CLICK_ACTION_KEY, clickAction)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.app_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    baseContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Hotelly Notifications"
            val descriptionText = "Hotelly 앱 알림 채널"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

}