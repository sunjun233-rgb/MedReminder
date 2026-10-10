package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var tempPhotoFile: File? = null
    private var currentMedForCheckIn: String = "日常服药"
    private lateinit var recordsTextView: TextView

    // 拍照回调
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempPhotoFile != null && tempPhotoFile!!.exists()) {
            saveRecord(currentMedForCheckIn, tempPhotoFile!!.absolutePath)
            Toast.makeText(this, "打卡成功！照片已存入App私有目录", Toast.LENGTH_SHORT).show()
            refreshRecordDisplay()
        } else {
            Toast.makeText(this, "拍照取消或未保存", Toast.LENGTH_SHORT).show()
        }
    }

    // 权限请求回调
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        // 纯代码动态构建界面，无需额外 XML 布局
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFF7F9FC.toInt())
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }
        scrollView.addView(rootLayout)
        setContentView(scrollView)

        // 标题
        val titleView = TextView(this).apply {
            text = "定时服药打卡助手"
            textSize = 24f
            setTextColor(0xFF1E293B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        rootLayout.addView(titleView)

        // 按钮 1：设置服药提醒计划
        val btnAddPlan = Button(this).apply {
            text = "➕ 添加服药提醒计划"
            setBackgroundColor(0xFF2563EB.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { showAddPlanDialog() }
        }
        rootLayout.addView(btnAddPlan)

        // 按钮 2：立即拍照打卡
        val btnCheckIn = Button(this).apply {
            text = "📷 立即拍照打卡"
            setBackgroundColor(0xFF059669.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 25 }
            layoutParams = params
            setOnClickListener { triggerPhotoCheckIn() }
        }
        rootLayout.addView(btnCheckIn)

        // 按钮 3：导出打卡记录为 Excel
        val btnExport = Button(this).apply {
            text = "📊 导出记录为 Excel (CSV)"
            setBackgroundColor(0xFFD97706.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 25 }
            layoutParams = params
            setOnClickListener { exportToExcelCsv() }
        }
        rootLayout.addView(btnExport)

        // 打卡记录列表展示标题
        val listTitle = TextView(this).apply {
            text = "\n📋 最近打卡记录（照片保存在私有目录）："
            textSize = 16f
            setTextColor(0xFF334155.toInt())
            setPadding(0, 30, 0, 15)
        }
        rootLayout.addView(listTitle)

        // 打卡记录内容
        recordsTextView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF475569.toInt())
            text = "暂无打卡记录"
        }
        rootLayout.addView(recordsTextView)

        refreshRecordDisplay()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // 弹出添加计划对话框
    private fun showAddPlanDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val nameInput = EditText(this).apply { hint = "药品名称（例如：降压药 / 维生素）" }
        val dosageInput = EditText(this).apply { hint = "剂量（例如：1片 / 2粒）" }
        layout.addView(nameInput)
        layout.addView(dosageInput)

        var selectedHour = 8
        var selectedMinute = 30

        val timeBtn = Button(this).apply {
            text = "选择提醒时间 (默认 08:30)"
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    selectedHour = h
                    selectedMinute = m
                    text = String.format(Locale.getDefault(), "提醒时间：%02d:%02d", h, m)
                }, selectedHour, selectedMinute, true).show()
            }
        }
        layout.addView(timeBtn)

        AlertDialog.Builder(this)
            .setTitle("设置服药计划")
            .setView(layout)
            .setPositiveButton("保存计划") { _, _ ->
                val medName = nameInput.text.toString().ifEmpty { "定时服药" }
                val dosage = dosageInput.text.toString().ifEmpty { "1次" }
                setAlarm(medName, dosage, selectedHour, selectedMinute)
                Toast.makeText(this, "闹钟已设置：每天 $selectedHour:$selectedMinute 提醒 $medName", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 设置精准定时闹钟
    private fun setAlarm(medName: String, dosage: String, hour: Int, minute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("MED_NAME", medName)
            putExtra("DOSAGE", dosage)
        }
        val reqCode = (medName.hashCode() + hour * 100 + minute) and 0x7FFFFFFF
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    // 触发拍照打卡
    private fun triggerPhotoCheckIn() {
        val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
        currentMedForCheckIn = sp.getString("last_med", "定时服药") ?: "定时服药"

        // 私有目录存储，卸载自动随 App 清除
        val privateDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val photoFile = File(privateDir, "MED_${timeStamp}.jpg")
        tempPhotoFile = photoFile

        val photoUri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        takePhotoLauncher.launch(photoUri)
    }

    // 保存记录到本地 JSON
    private fun saveRecord(medName: String, photoPath: String) {
        val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
        val existingJson = sp.getString("records", "[]") ?: "[]"
        val array = JSONArray(existingJson)

        val newRecord = JSONObject().apply {
            put("med", medName)
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("photo", photoPath)
        }
        array.put(newRecord)
        sp.edit().putString("records", array.toString()).apply()
    }

    // 刷新界面记录
    private fun refreshRecordDisplay() {
        val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
        val existingJson = sp.getString("records", "[]") ?: "[]"
        val array = JSONArray(existingJson)

        if (array.length() == 0) {
            recordsTextView.text = "暂无打卡记录，点击上方绿色按钮进行拍照打卡。"
            return
        }

        val sb = StringBuilder()
        for (i in array.length() - 1 downTo 0) {
            val item = array.getJSONObject(i)
            sb.append("• 时间: ").append(item.getString("time")).append("\n")
            sb.append("  药品: ").append(item.getString("med")).append("\n")
            sb.append("  照片已存私有区: ").append(File(item.getString("photo")).name).append("\n\n")
        }
        recordsTextView.text = sb.toString()
    }

    // 导出打卡记录为 Excel (带 UTF-8 BOM 的 CSV 格式)
    private fun exportToExcelCsv() {
        val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
        val existingJson = sp.getString("records", "[]") ?: "[]"
        val array = JSONArray(existingJson)

        if (array.length() == 0) {
            Toast.makeText(this, "暂无打卡数据可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val docDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val file = File(docDir, "服药打卡记录_${System.currentTimeMillis()}.csv")

        FileOutputStream(file).use { fos ->
            fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // 写入 BOM
            OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                writer.append("药品名称,打卡时间,照片绝对路径\n")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    writer.append("\"${item.getString("med")}\",")
                    writer.append("\"${item.getString("time")}\",")
                    writer.append("\"${item.getString("photo")}\"\n")
                }
                writer.flush()
            }
        }

        // 调起系统分享，可直接发送到微信/QQ或保存到手机文件管理器
        val fileUri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "导出打卡记录 Excel 文件"))
    }
}
