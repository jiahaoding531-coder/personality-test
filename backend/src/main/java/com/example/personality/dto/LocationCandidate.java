package com.example.personality.dto;

/** 真实 POI 候选；坐标只适用于已经约定 GCJ02 的餐饮链路。 */
public record LocationCandidate(String poiId, String name, String address,
        String province, String city, String district, double latitude, double longitude,
        String coordinateSystem, String source) {
    @Override public String toString() { return "LocationCandidate[坐标已隐藏]"; }
}
