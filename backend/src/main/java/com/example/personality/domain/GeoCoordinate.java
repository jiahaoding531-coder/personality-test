package com.example.personality.domain;

/** 不可变坐标值，相当于带校验的 Python dataclass；保留输入精度。 */
public record GeoCoordinate(double latitude, double longitude, CoordinateSystem coordinateSystem) {
    public GeoCoordinate {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180
                || coordinateSystem == null) {
            throw new IllegalArgumentException("坐标无效或未指定坐标系");
        }
    }

    /** 避免业务日志无意打印 record 时泄露精确坐标。 */
    @Override
    public String toString() {
        return "GeoCoordinate[coordinateSystem=" + coordinateSystem + ", position=已隐藏]";
    }
}
