package com.mola.cmd.proxy.app.utils;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecSqlUtil {

    public static String execSql(String sql, String url, String username, String password) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            
            Connection connection = DriverManager.getConnection(url + "?characterEncoding=utf-8&useSSL=false", username, password);
            Statement statement = connection.createStatement();
            
            if (sql.trim().toLowerCase().startsWith("update") || sql.trim().toLowerCase().startsWith("delete") || sql.trim().toLowerCase().startsWith("insert")) {
                int result = statement.executeUpdate(sql);
                statement.close();
                connection.close();

                return "执行成功，影响行数: " + result;
            } else {
                ResultSet resultSet = statement.executeQuery(sql);
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnName(i));
                }

                List<Map<String, Object>> data = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(columns.get(i-1), resultSet.getObject(i));
                    }
                    data.add(row);
                }

                resultSet.close();
                statement.close();
                connection.close();

                return formatAsJson(columns, data);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "执行SQL时出错: " + e.getMessage();
        }
    }
    
    private static String formatAsJson(List<String> columns, List<Map<String, Object>> data) {
        StringBuilder json = new StringBuilder("{\n  \"columns\": [");
        
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append("\"").append(columns.get(i)).append("\"");
        }
        json.append("],\n  \"data\": [\n");
        
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            json.append("    {");
            
            boolean firstField = true;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (!firstField) {
                    json.append(", ");
                }
                json.append("\"").append(entry.getKey()).append("\": ");
                
                Object value = entry.getValue();
                if (value == null) {
                    json.append("null");
                } else if (value instanceof String) {
                    json.append("\"").append(value).append("\"");
                } else {
                    json.append(value);
                }
                firstField = false;
            }
            
            json.append("}");
            if (i < data.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        
        json.append("  ]\n}");
        return json.toString();
    }
}