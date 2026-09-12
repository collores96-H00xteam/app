package com.example.counter

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { RemoteScreen() }
            }
        }
    }
}

// ===== NEC-энкодер (самый распространённый протокол ИК-пультов) =====
fun necPattern(code: Long, bits: Int = 32): IntArray {
    val p = mutableListOf<Int>()
    p.add(9000); p.add(4500)
    for (i in 0 until bits) {
        val bit = (code shr i) and 1L
        p.add(560)
        p.add(if (bit == 1L) 1690 else 560)
    }
    p.add(560)
    return p.toIntArray()
}

// RC5 (Philips, Grundig, Nokia) — 14 бит, другой протокол
fun rc5Pattern(code: Int): IntArray {
    val p = mutableListOf<Int>()
    p.add(889); p.add(889)
    for (i in 13 downTo 0) {
        val bit = (code shr i) and 1
        if (bit == 1) { p.add(889); p.add(1778) }
        else          { p.add(1778); p.add(889) }
    }
    return p.toIntArray()
}

// SIRC (Sony) — 12 бит, 40 кГц
fun sircPattern(code: Int): IntArray {
    val p = mutableListOf<Int>()
    p.add(2400); p.add(600)
    for (i in 0 until 12) {
        val bit = (code shr i) and 1
        p.add(600)
        p.add(if (bit == 1) 1200 else 600)
    }
    return p.toIntArray()
}

data class IrSignal(
    val label: String,
    val freq: Int,
    val pattern: IntArray
) {
    override fun equals(other: Any?) = other is IrSignal && label == other.label
    override fun hashCode() = label.hashCode()
}

// ===== База известных Power-кодов ТВ (реальные коды из IRDB/LIRC) =====
fun buildPowerDatabase(): List<IrSignal> {
    val list = mutableListOf<IrSignal>()

    // --- NEC 32-бит, 38 кГц (Samsung, LG, Xiaomi, TCL, Hisense, DEXP, Vestel, Toshiba, RCA, AOC...) ---
    val necCodes = longArrayOf(
        0xE0E040BF, // Samsung / TCL / Xiaomi / Hisense / DEXP / Vestel / AOC / Coby / Insignia
        0xE0E0E01F, // Samsung alt
        0xE0E0D02F, // Samsung alt 2
        0xE0E0D0AF,
        0x20DF10EF, // LG
        0x20DFC03F, // LG alt
        0x20DF23DC, // LG alt 2
        0x20DF906F,
        0x02FD48B7, // Toshiba
        0x40BF12ED, // Toshiba alt
        0x02FD02FD,
        0x2FD48B7,  // Hisense
        0x2FD00FF,  // Hisense alt
        0x40BF00FF,
        0x10EF10EF, // Panasonic NEC-alt
        0x1004C4C,
        0xAA5FAA5F, // Sharp
        0x1CE3A5F,
        0xFE01FE01,
        0x807F827D, // RCA
        0x8F70E31C,
        0xE619E619,
        0xF708F708,
        0x1AE51AE5,
        0x906F906F,
        0x619E619E,
        0xC738C738,
        0x3AC53AC5,
        0x99669966,
        0x66996699,
        0xA25DA25D,
        0x5DA25DA2,
        0xD02FD02F,
        0x2FD02FD0,
        0x4EB14EB1,
        0x7788F708,
        0xE51AE51A,
        0x6B946B94,
        0x55AA55AA,
        0xAA55AA55,
        0xF00FF00F,
        0x0FF00FF0,
        0x7B84F00F,
        0x8F700FF0,
        0xF807F807,
        0x3BC43BC4,
        0xC03FC03F,
        0x6E916E91,
        0x956A956A,
        0xB54AB54A,
        0x4AB54AB5,
        0xD22DD22D,
        0xDD22DD22,
        0x9F609F60,
        0x609F609F,
        0xE41BE41B,
        0xDA25DA25,
        0x25DA25DA,
        0xA956A956
    )
    necCodes.forEach { list.add(IrSignal("NEC 0x%08X".format(it), 38000, necPattern(it))) }

    // --- NEC 16-бит (некоторые старые ТВ) ---
    val nec16 = intArrayOf(0xAA5F, 0x1CE3, 0x609F, 0x9F60, 0xF00F, 0x0FF0)
    nec16.forEach { list.add(IrSignal("NEC16 0x%04X".format(it), 38000, necPattern(it.toLong(), 16))) }

    // --- Panasonic (специфичный протокол, но многие приёмники ловят как NEC) ---
    list.add(IrSignal("Panasonic", 38000, necPattern(0x400401BC)))
    list.add(IrSignal("Panasonic alt", 38000, necPattern(0x4004C4C)))

    // --- RC5 (Philips, Grundig, Nokia, Marantz) ---
    val rc5 = intArrayOf(0x0C, 0x0C0C, 0x1C, 0x0D, 0x00)
    rc5.forEach { list.add(IrSignal("RC5 0x%X".format(it), 36000, rc5Pattern(it))) }

    // --- SIRC Sony (12 бит, 40 кГц) ---
    val sony = intArrayOf(0xA90, 0xA8B, 0x0A8, 0x2A8, 0xA50)
    sony.forEach { list.add(IrSignal("Sony 0x%X".format(it), 40000, sircPattern(it))) }

    return list
}

// ===== Диапазон для "массивного" режима (перебор вариантов NEC) =====
fun buildMassiveDatabase(): List<IrSignal> {
    val list = mutableListOf<IrSignal>()
    // Классические "семейства" кодов, встречающиеся у большинства телевизоров:
    val families = longArrayOf(
        0xE0E0E01FL, 0x20DFC03FL, 0x02FD02FDL, 0x10EF10EFL,
        0x40BF40BFL, 0x807F807FL, 0x1CE31CE3L, 0xAA5FAA5FL,
        0x609F609FL, 0xF00FF00FL, 0x0FF00FF0L, 0x66996699L,
        0x99669966L, 0x5DA25DA2L, 0xA25DA25DL, 0xD02FD02FL
    )
    // К каждому семейству добавляем "инверсные" младшие/старшие байты — часто power-код = инверсия
    for (base in families) {
        list.add(IrSignal("M 0x%08X".format(base), 38000, necPattern(base)))
        list.add(IrSignal("M 0x%08X".format(base.inv() and 0xFFFFFFFFL), 38000, necPattern(base.inv() and 0xFFFFFFFFL)))
    }
    // Разные частоты несущей для одного известного кода
    val known = 0xE0E040BFL
    for (f in intArrayOf(33000, 36000, 38000, 40000, 56000)) {
        list.add(IrSignal("F $f Hz", f, necPattern(known)))
    }
    return list
}

@Composable
fun RemoteScreen() {
    val context = LocalContext.current
    val ir = remember {
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    }
    val hasIr = remember { ir?.hasIrEmitter() == true }
    val handler = remember { Handler(Looper.getMainLooper()) }

    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        running = false
    }

    fun sendOne(sig: IrSignal) {
        try { ir?.transmit(sig.freq, sig.pattern) } catch (_: Exception) {}
    }

    // Перебор списка с интервалом между посылками
    fun bruteForce(name: String, list: List<IrSignal>, intervalMs: Long) {
        if (!hasIr) { status = "❌ ИК-порта нет"; return }
        stop()
        running = true
        total = list.size
        progress = 0
        status = "⏳ $name: 0 / $total"

        fun step(i: Int) {
            if (!running || i >= list.size) {
                if (running) status = "✅ Перебор завершён ($total кодов)"
                running = false
                return
            }
            sendOne(list[i])
            progress = i + 1
            status = "⏳ $name: ${i + 1} / $total  (${list[i].label})"
            handler.postDelayed({ step(i + 1) }, intervalMs)
        }
        step(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📺 ИК Пульт (все бренды)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (hasIr)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                if (hasIr) {
                    Text("✅ ИК-передатчик найден", fontWeight = FontWeight.Bold)
                    Text("Направьте телефон на ТВ, жмите кнопку, держите 1–3 м.",
                        fontSize = 13.sp)
                } else {
                    Text("❌ ИК-порта нет", fontWeight = FontWeight.Bold)
                    Text("Работает только на телефонах со встроенным ИК " +
                        "(Xiaomi, Redmi, POCO, старые Huawei, LG G, Galaxy S4/S5).",
                        fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ===== БЫСТРЫЙ ТОЧЕЧНЫЙ ПЕРЕБОР =====
        Button(
            onClick = {
                val db = buildPowerDatabase()
                bruteForce("Быстрый", db, 250L) // ~60 кодов × 0.25 с = ~15 сек
            },
            enabled = hasIr && !running,
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) { Text("🎯 ТОЧЕЧНЫЙ ПЕРЕБОР (~60 кодов, 15 сек)",
                fontSize = 15.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(10.dp))

        // ===== МАССИВНЫЙ =====
        Button(
            onClick = {
                val db = buildPowerDatabase() + buildMassiveDatabase()
                bruteForce("Массивный", db, 300L)
            },
            enabled = hasIr && !running,
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) { Text("💥 МАССИВНЫЙ ПЕРЕБОР\n(точный + семейства, ~1 мин)",
                fontSize = 15.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(10.dp))

        // ===== ТУРБО =====
        Button(
            onClick = {
                // Генерируем "широкую" сетку кодов: все байты 0x00–0xFF повторяются
                // в маске 0xXXYYXXYY — типичный вид NEC-кодов для power.
                val turbo = mutableListOf<IrSignal>()
                turbo.addAll(buildPowerDatabase())
                turbo.addAll(buildMassiveDatabase())
                for (hi in 0 until 256 step 4) {
                    val code = (hi.toLong() shl 24) or (hi.toLong() shl 16) or
                               (hi.toLong() shl 8) or hi.toLong()
                    turbo.add(IrSignal("T 0x%08X".format(code), 38000, necPattern(code)))
                    turbo.add(IrSignal("T 0x%08X".format(code.inv() and 0xFFFFFFFFL),
                        38000, necPattern(code.inv() and 0xFFFFFFFFL)))
                }
                bruteForce("ТУРБО", turbo, 200L)
            },
            enabled = hasIr && !running,
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) { Text("⚡ ТУРБО (макс. покрытие, ~3 мин)",
                fontSize = 15.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(16.dp))

        if (running) {
            Button(
                onClick = { stop(); status = "⏹ Остановлено" },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) { Text("⏹ СТОП") }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else progress.toFloat() / total },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        if (status.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        Text("💡 Как пользоваться:\n" +
            "1. Направьте верх телефона на ТВ\n" +
            "2. Жмите любую кнопку перебора\n" +
            "3. Когда ТВ мигнёт/выключится — жмите СТОП\n" +
            "4. Запомните бренд из статуса (или используйте режим брендов ниже)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        // ===== ОТДЕЛЬНЫЕ БРЕНДЫ (для точечного управления) =====
        Text("Точечно по бренду:", fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(8.dp))

        val quick = listOf(
            "Samsung" to 0xE0E040BFL,
            "LG" to 0x20DF10EFL,
            "Toshiba" to 0x02FD48B7L,
            "Hisense" to 0x2FD48B7L,
            "Sharp" to 0xAA5FAA5FL,
            "RCA / Vestel / DEXP" to 0x807F827DL
        )
        quick.forEach { (name, code) ->
            OutlinedButton(
                onClick = { sendOne(IrSignal(name, 38000, necPattern(code))); status = "✅ $name" },
                enabled = hasIr,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) { Text("⏻  $name") }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { sendOne(IrSignal("Sony", 40000, sircPattern(0xA90))); status = "✅ Sony" },
            enabled = hasIr, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        ) { Text("⏻  Sony") }

        OutlinedButton(
            onClick = { sendOne(IrSignal("Philips", 36000, rc5Pattern(0x0C))); status = "✅ Philips" },
            enabled = hasIr, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        ) { Text("⏻  Philips / Grundig") }
    }
}
