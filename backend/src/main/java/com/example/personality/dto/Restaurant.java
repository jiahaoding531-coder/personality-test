package com.example.personality.dto;
import java.util.List;

/** 高德餐饮事实。包装类型允许未知值为 null，不用零或推测信息填充。 */
public record Restaurant(String poiId, String name, String type, String typeCode,
        String address, Double latitude, Double longitude, Integer distanceMeters,
        Double averageCost, String openingHoursText, String telephone,
        String imageUrl, Double rating, List<String> tags) {}
