package edu.multisense.collector;

import android.hardware.Sensor;
import org.json.JSONObject;

final class SensorCatalog {
    static String name(int type) {
        return switch (type) {
            case 1 -> "加速度"; case 2 -> "磁场"; case 3 -> "方向角"; case 4 -> "角速度";
            case 5 -> "光照"; case 6 -> "气压"; case 7 -> "设备温度"; case 8 -> "接近距离";
            case 9 -> "重力"; case 10 -> "线性加速度"; case 11 -> "旋转向量";
            case 12 -> "相对湿度"; case 13 -> "环境温度"; case 14 -> "未校准磁场";
            case 15 -> "游戏旋转向量"; case 16 -> "未校准角速度"; case 17 -> "显著运动";
            case 18 -> "步态事件"; case 19 -> "累计步数"; case 20 -> "地磁旋转向量";
            case 21 -> "心率"; case 22 -> "倾斜事件"; case 23 -> "唤醒手势";
            case 24 -> "拾起手势"; case 25 -> "扫视手势"; case 28 -> "姿态 6DoF";
            case 29 -> "静止事件"; case 30 -> "运动事件"; case 31 -> "心跳事件";
            case 34 -> "离身检测"; case 35 -> "未校准加速度"; case 36 -> "铰链角度";
            case 37 -> "头部跟踪"; case 38 -> "加速度限制轴"; case 39 -> "角速度限制轴";
            case 40 -> "未校准加速度限制轴"; case 41 -> "未校准角速度限制轴"; case 42 -> "航向";
            default -> "传感器 " + type;
        };
    }
    static String unit(int type) {
        return switch (type) {
            case 1, 9, 10, 35, 38, 40 -> "m/s²"; case 4, 16, 39, 41 -> "rad/s";
            case 2, 14 -> "µT"; case 5 -> "lx"; case 6 -> "hPa"; case 8 -> "cm";
            case 7, 13 -> "°C"; case 12 -> "%"; case 3, 36, 42 -> "°";
            case 19 -> "步（设备累计）"; case 21 -> "bpm"; default -> "原生值";
        };
    }
    static JSONObject describe(Sensor sensor, String id) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("type", sensor.getType()).put("title", name(sensor.getType()))
                .put("name", sensor.getName()).put("vendor", sensor.getVendor()).put("version", sensor.getVersion())
                .put("stringType", sensor.getStringType()).put("unit", unit(sensor.getType()))
                .put("range", sensor.getMaximumRange()).put("resolution", sensor.getResolution())
                .put("powerMa", sensor.getPower()).put("minDelayUs", sensor.getMinDelay())
                .put("maxDelayUs", sensor.getMaxDelay()).put("reportingMode", sensor.getReportingMode())
                .put("wakeUp", sensor.isWakeUpSensor()).put("hardwareId", sensor.getId())
                .put("permission", sensor.getType()==21 ? "BODY_SENSORS" : sensor.getType()==18 || sensor.getType()==19 ? "ACTIVITY_RECOGNITION" : "由系统决定");
        } catch (Exception ignored) { }
        return o;
    }
}
