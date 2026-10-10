package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.AlarmClock
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
    private var lastQuickClickTime: Long = 0
    private var currentDisplayYear: Int = 0
    private var currentDisplayMonth: Int = 0

    private lateinit var calendarGrid: GridLayout
    private lateinit var monthTitleView: TextView
    private lateinit var recordsLayout: LinearLayout

    // 拍照完成回调
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempPhotoFile != null && tempPhotoFile!!.exists()) {
            saveRecord("常规服药", tempPhotoFile!!.absolutePath, true)
            Toast.makeText(this, "拍照打卡成功！已在日历记录", Toast.LENGTH_SHORT).show()
            refreshAllViews()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val now = Calendar.getInstance()
        currentDisplayYear = now.get(Calendar.YEAR)
        currentDisplayMonth = now.get(Calendar.MONTH)

        checkPermissions()

        val rootScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#F1F5F9"))
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 60)
        }
        rootScroll.addView(rootLayout)
        setContentView(rootScroll)

        // 顶部标题
        val appTitle = TextView(this).apply {
            text = "💊 定时服药打卡助手"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 30)
        }
        rootLayout.addView(appTitle)

        // 操作按钮组
        val btnAddPlan = createStyledButton("⏰ 添加服药提醒 (可联动系统时钟)", "#2563EB") {
            showAddPlanDialog()
        }
        rootLayout.addView(btnAddPlan)

        val btnPhoto = createStyledButton("📷 拍照打卡", "#059669") {
            triggerPhotoCheckIn()
        }
        rootLayout.addView(btnPhoto)

        val btnQuick = createStyledButton("⚡ 连续双击打卡 (免拍照)", "#475569") {
            handleQuickCheckIn()
        }
        rootLayout.addView(btnQuick)

        val btnExport = createStyledButton("📊 导出打卡记录为 Excel (CSV)", "#D97706") {
            exportToExcelCsv()
        }
        rootLayout.addView(btnExport)

        // 日历卡片容器
        val calendarCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 40 }
            layoutParams = params
        }

        // 月份切换控制栏
        val monthHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnPrev = Button(this).apply {
            text = "◀ 上月"
            textSize = 12f
            setOnClickListener {
                currentDisplayMonth--
                if (currentDisplayMonth < 0) {
                    currentDisplayMonth = 11
                    currentDisplayYear--
                }
                refreshAllViews()
            }
        }
        monthTitleView = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNext = Button(this).apply {
            text = "下月 ▶"
            textSize = 12f
            setOnClickListener {
                currentDisplayMonth++
                if (currentDisplayMonth > 11) {
                    currentDisplayMonth = 0
                    currentDisplayYear++
                }
                refreshAllViews()
            }
        }
        monthHeader.addView(btnPrev)
        monthHeader.addView(monthTitleView)
        monthHeader.addView(btnNext)
        calendarCard.addView(monthHeader)

        // 星期栏
        val weekLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        for (day in weekDays) {
            val tv = TextView(this).apply {
                text = day
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#64748B"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            weekLayout.addView(tv)
        }
        calendarCard.addView(weekLayout)

        // 日历网格
        calendarGrid = GridLayout(this).apply {
            columnCount = 7
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        calendarCard.addView(calendarGrid)
        rootLayout.addView(calendarCard)

        // 打卡记录列表区
        val detailTitle = TextView(this).apply {
            text = "📋 打卡记录明细（点击可看照片）："
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(0, 40, 0, 16)
        }
        rootLayout.addView(detailTitle)

        recordsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootLayout.addView(recordsLayout)

        refreshAllViews()
    }

    private fun createStyledButton(title: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(colorHex))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // 刷新日历打勾状态与下方列表
    private fun refreshAllViews() {
        monthTitleView.text = String.format(Locale.getDefault(), "%d年 %d月", currentDisplayYear, currentDisplayMonth + 1)
        calendarGrid.removeAllViews()

        val checkedDates = getCheckedInDatesSet()

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, currentDisplayYear)
            set(Calendar.MONTH, currentDisplayMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }

        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // 填充月首空白
        for (i in 0 until firstDayOfWeek) {
            val emptyTv = TextView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 130
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            }
            calendarGrid.addView(emptyTv)
        }

        // 填充每个日期格子
        for (day in 1..maxDay) {
            val dateKey = String.format(Locale.getDefault(), "%04d-%02d-%02d", currentDisplayYear, currentDisplayMonth + 1, day)
            val isChecked = checkedDates.contains(dateKey)

            val dayView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 130
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
                if (isChecked) {
                    setBackgroundColor(Color.parseColor("#DCFCE7")) // 打卡成功浅绿背景
                }
            }

            val tvNum = TextView(this).apply {
                text = day.toString()
                textSize = 12f
                setTextColor(Color.parseColor("#1E293B"))
                gravity = Gravity.CENTER
            }
            dayView.addView(tvNum)

            if (isChecked) {
                val tvCheck = TextView(this).apply {
                    text = "✔"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#16A34A")) // 打勾标识
                    gravity = Gravity.CENTER
                }
                dayView.addView(tvCheck)
            }

            calendarGrid.addView(dayView)
        }

        renderRecordsList()
    }

    private fun getCheckedInDatesSet(): Set<String> {
        val set = HashSet<String>()
        val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
        val array = JSONArray(sp.getString("records", "[]") ?: "[]")
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val time = obj.optString("time", "")
            if (time.length >= 10) {
                set.add(time.substring(0, 10))
            }
        }
        return set
    }

    private fun renderRecordsList() {
        recordsLayout.removeAllViews()
        val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
        val array = JSONArray(sp.getString("records", "[]") ?: "[]")

        if (array.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = "暂无打卡记录"
                setTextColor(Color.parseColor("#64748B"))
            }
            recordsLayout.addView(emptyTv)
            return
        }

        for (i in array.length() - 1 downTo 0) {
            val item = array.getJSONObject(i)
            val time = item.optString("time")
            val med = item.optString("med")
            val photoPath = item.optString("photo")
            val hasPhoto = item.optBoolean("hasPhoto", false)

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                layoutParams = lp
            }

            val titleTv = TextView(this).apply {
                text = "时间：$time  |  $med"
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            }
            itemLayout.addView(titleTv)

            if (hasPhoto && photoPath.isNotEmpty() && File(photoPath).exists()) {
                val btnViewPhoto = TextView(this).apply {
                    text = "📸 点击查看打卡照片"
                    setTextColor(Color.parseColor("#2563EB"))
                    setPadding(0, 10, 0, 0)
                    setOnClickListener { showPhotoDialog(photoPath) }
                }
                itemLayout.addView(btnViewPhoto)
            } else {
                val noPhotoTv = TextView(this).apply {
                    text = "⚡ 快捷打卡（无照片）"
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, 6, 0, 0)
                }
                itemLayout.addView(noPhotoTv)
            }

            recordsLayout.addView(itemLayout)
        }
    }

    // 随时弹窗查看已保存的照片
    private fun showPhotoDialog(path: String) {
        val imgView = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = 1200
            val bitmap = BitmapFactory.decodeFile(path)
            setImageBitmap(bitmap)
        }
        AlertDialog.Builder(this)
            .setTitle("打卡照片详情")
            .setView(imgView)
            .setPositiveButton("关闭", null)
            .show()
    }

    // 两次点击快捷打卡（2秒内连按）
    private fun handleQuickClickCheckIn() {
        val now = System.currentTimeMillis()
        if (now - lastQuickClickTime < 2000) {
            saveRecord("快捷服药(免拍照)", "", false)
            Toast.makeText(this, "快捷打卡成功！已在日历标注", Toast.LENGTH_SHORT).show()
            lastQuickClickTime = 0
            refreshAllViews()
        } else {
            lastQuickClickTime = now
            Toast.makeText(this, "请在 2 秒内再点一次确认打卡", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleQuickCheckIn() {
        handleQuickClickCheckIn()
    }

    // 调起相机
    private fun triggerPhotoCheckIn() {
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

    private fun saveRecord(medName: String, photoPath: String, hasPhoto: Boolean) {
        val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
        val existingJson = sp.getString("records", "[]") ?: "[]"
        val array = JSONArray(existingJson)

        val newRecord = JSONObject().apply {
            put("med", medName)
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("photo", photoPath)
            put("hasPhoto", hasPhoto)
        }
        array.put(newRecord)
        sp.edit().putString("records", array.toString()).apply()
    }

    // 添加服药计划并联动闹钟
    private fun showAddPlanDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val nameInput = EditText(this).apply { hint = "药品名称（例如：降压药）" }
        val dosageInput = EditText(this).apply { hint = "剂量（例如：每次1片）" }
        val syncSystemAlarmCheck = CheckBox(this).apply {
            text = "同步添加到手机系统自带时钟闹钟"
            isChecked = true
        }

        layout.addView(nameInput)
        layout.addView(dosageInput)
        layout.addView(syncSystemAlarmCheck)

        var hour = 8
        var minute = 30

        val timeBtn = Button(this).apply {
            text = "选择提醒时间 (默认 08:30)"
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    hour = h
                    minute = m
                    text = String.format(Locale.getDefault(), "提醒时间：%02d:%02d", h, m)
                }, hour, minute, true).show()
            }
        }
        layout.addView(timeBtn)

        AlertDialog.Builder(this)
            .setTitle("设置服药计划")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().ifEmpty { "定时服药" }
                val dosage = dosageInput.text.toString().ifEmpty { "1次" }

                setInternalAlarm(name, dosage, hour, minute)

                if (syncSystemAlarmCheck.isChecked) {
                    setSystemClockAlarm(name, hour, minute)
                }

                Toast.makeText(this, "闹钟已设置：每天 $hour:$minute", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setInternalAlarm(name: String, dosage: String, hour: Int, minute: Int) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("MED_NAME", name)
            putExtra("DOSAGE", dosage)
        }
        val pi = PendingIntent.getBroadcast(
            this,
            (name.hashCode() + hour * 60 + minute) and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    private fun setSystemClockAlarm(name: String, hour: Int, minute: Int) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "服药: $name")
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            startActivity(intent)
        } catch (_: Exception) { }
    }

    // 导出 Excel (带 BOM，修复闪退问题)
    private fun exportToExcelCsv() {
        try {
            val sp = getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
            val array = JSONArray(sp.getString("records", "[]") ?: "[]")

            if (array.length() == 0) {
                Toast.makeText(this, "暂无打卡数据可导出", Toast.LENGTH_SHORT).show()
                return
            }

            val docDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(docDir, "服药打卡记录_${System.currentTimeMillis()}.csv")

            FileOutputStream(file).use { fos ->
                fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.append("药品名称,打卡时间,打卡方式,照片绝对路径\n")
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val hasPhoto = item.optBoolean("hasPhoto", false)
                        val method = if (hasPhoto) "拍照打卡" else "快捷打卡"
                        writer.append("\"${item.optString("med")}\",")
                        writer.append("\"${item.optString("time")}\",")
                        writer.append("\"$method\",")
                        writer.append("\"${item.optString("photo")}\"\n")
                    }
                    writer.flush()
                }
            }

            val fileUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "发送/保存打卡记录"))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }
}
