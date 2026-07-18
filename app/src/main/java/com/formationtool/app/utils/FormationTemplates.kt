package com.formationtool.app.utils

import com.formationtool.app.model.Dancer
import com.formationtool.app.model.Formation
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

/**
 * 队形模板 - 提供常用队形自动生成
 */
object FormationTemplates {

    /**
     * 创建直线队形
     */
    fun createLineFormation(dancers: List<Dancer>, spacing: Float = 2f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "直线队形"
        )

        dancers.forEachIndexed { index, dancer ->
            val newDancer = dancer.copy(
                x = (index - dancers.size / 2f) * spacing,
                y = 0f,
                z = 0f
            )
            formation.dancers.add(newDancer)
        }

        return formation
    }

    /**
     * 创建圆形队形
     */
    fun createCircleFormation(dancers: List<Dancer>, radius: Float = 4f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "圆形队形"
        )

        dancers.forEachIndexed { index, dancer ->
            val angle = 2 * Math.PI * index / dancers.size
            val newDancer = dancer.copy(
                x = (radius * cos(angle)).toFloat(),
                y = (radius * sin(angle)).toFloat(),
                z = 0f
            )
            formation.dancers.add(newDancer)
        }

        return formation
    }

    /**
     * 创建V字队形
     */
    fun createVFormation(dancers: List<Dancer>, spacing: Float = 2f, angle: Float = 45f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "V字队形"
        )

        val halfCount = dancers.size / 2
        dancers.forEachIndexed { index, dancer ->
            val side = if (index < halfCount) -1 else 1
            val position = if (index < halfCount) index else index - halfCount

            val angleRad = Math.toRadians(angle.toDouble())
            val x = side * position * spacing * cos(angleRad).toFloat()
            val y = position * spacing * sin(angleRad).toFloat()

            val newDancer = dancer.copy(x = x, y = y, z = 0f)
            formation.dancers.add(newDancer)
        }

        return formation
    }

    /**
     * 创建矩形队形
     */
    fun createRectangleFormation(dancers: List<Dancer>, columns: Int, spacing: Float = 2f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "矩形队形"
        )

        dancers.forEachIndexed { index, dancer ->
            val col = index % columns
            val row = index / columns

            val newDancer = dancer.copy(
                x = (col - columns / 2f) * spacing,
                y = (row - (dancers.size / columns) / 2f) * spacing,
                z = 0f
            )
            formation.dancers.add(newDancer)
        }

        return formation
    }

    /**
     * 创建三角形队形
     */
    fun createTriangleFormation(dancers: List<Dancer>, spacing: Float = 2f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "三角形队形"
        )

        var currentRow = 0
        var dancersInRow = 1
        var dancerIndex = 0

        while (dancerIndex < dancers.size) {
            for (i in 0 until dancersInRow) {
                if (dancerIndex >= dancers.size) break

                val dancer = dancers[dancerIndex]
                val newDancer = dancer.copy(
                    x = (i - (dancersInRow - 1) / 2f) * spacing,
                    y = currentRow * spacing * 0.866f,  // sqrt(3)/2 for equilateral
                    z = 0f
                )
                formation.dancers.add(newDancer)
                dancerIndex++
            }
            currentRow++
            dancersInRow++
        }

        return formation
    }

    /**
     * 创建波浪队形
     */
    fun createWaveFormation(dancers: List<Dancer>, wavelength: Float = 4f, amplitude: Float = 2f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "波浪队形"
        )

        dancers.forEachIndexed { index, dancer ->
            val x = (index - dancers.size / 2f) * 1.5f
            val y = amplitude * sin(2 * Math.PI * x / wavelength).toFloat()

            val newDancer = dancer.copy(x = x, y = y, z = 0f)
            formation.dancers.add(newDancer)
        }

        return formation
    }

    /**
     * 创建心形队形
     */
    fun createHeartFormation(dancers: List<Dancer>, scale: Float = 2f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "心形队形"
        )

        dancers.forEachIndexed { index, dancer ->
            val t = 2 * Math.PI * index / dancers.size

            // Heart curve parametric equations
            val x = scale * 16 * sin(t).pow(3).toFloat()
            val y = scale * (13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)).toFloat()

            val newDancer = dancer.copy(
                x = x / 16f,
                y = y / 16f,
                z = 0f
            )
            formation.dancers.add(newDancer)
        }

        return formation
    }

    /**
     * 创建螺旋队形
     */
    fun createSpiralFormation(dancers: List<Dancer>, spacing: Float = 0.5f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "螺旋队形"
        )

        dancers.forEachIndexed { index, dancer ->
            val angle = index * 0.5
            val radius = index * spacing

            val newDancer = dancer.copy(
                x = (radius * cos(angle)).toFloat(),
                y = (radius * sin(angle)).toFloat(),
                z = 0f
            )
            formation.dancers.add(newDancer)
        }

        return formation
    }

    /**
     * 创建对角线队形
     */
    fun createDiagonalFormation(dancers: List<Dancer>, spacing: Float = 2f): Formation {
        val formation = Formation(
            id = UUID.randomUUID().toString(),
            name = "对角线队形"
        )

        dancers.forEachIndexed { index, dancer ->
            val offset = index - dancers.size / 2f
            val newDancer = dancer.copy(
                x = offset * spacing,
                y = offset * spacing,
                z = 0f
            )
            formation.dancers.add(newDancer)
        }

        return formation
    }
}
