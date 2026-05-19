package cn.upcshare.downloadsite.support;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Formatters {
    private Formatters() {}

    public static String size(long n) {
        double s = n;
        for (String unit : new String[]{"B", "KB", "MB", "GB"}) {
            if (s < 1024) return String.format("%.1f %s", s, unit);
            s /= 1024;
        }
        return String.format("%.1f TB", s);
    }

    public static Map<String, Object> fileDto(Map<String, Object> row) {
        Map<String, Object> dto = new LinkedHashMap<>(row);
        long raw = ((Number) row.getOrDefault("file_size", 0)).longValue();
        dto.put("file_size_raw", raw);
        dto.put("file_size", size(raw));
        dto.putIfAbsent("status", "approved");
        dto.putIfAbsent("uploader", "");
        return dto;
    }
}
