package com.weifu.app.utils;// GsonParser.java
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;

public class GsonParser {
    private static Gson gson;
    
    static {
        gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd HH:mm:ss") // 日期格式
                .serializeNulls() // 序列化null值
                .setPrettyPrinting() // 美化输出（调试用）
                .create();
    }
    
    // 解析单个对象
    public static <T> T fromJson(String json, Class<T> classOfT) {
        try {
            return gson.fromJson(json, classOfT);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    // 解析对象列表
    public static <T> List<T> fromJsonList(String json, Class<T> classOfT) {
        try {
            Type type = TypeToken.getParameterized(List.class, classOfT).getType();
            return gson.fromJson(json, type);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    
    // 解析复杂泛型（如ApiResponse<User>）
//    public static <T> ApiResponse<T> fromJsonApiResponse(String json, Class<T> dataClass) {
//        try {
//            Type type = TypeToken.getParameterized(ApiResponse.class, dataClass).getType();
//            return gson.fromJson(json, type);
//        } catch (Exception e) {
//            e.printStackTrace();
//            return null;
//        }
//    }
    
    // 对象转JSON
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }
}