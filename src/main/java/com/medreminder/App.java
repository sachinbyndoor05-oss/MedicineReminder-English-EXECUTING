package com.medreminder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class App {
    static final int PORT=8081;
    static final Path STATIC=Paths.get("src/main/resources/static").toAbsolutePath().normalize();
    static final Map<String,Long> SESSIONS=new ConcurrentHashMap<>();
    static final SecureRandom RNG=new SecureRandom();

    public static void main(String[] args)throws Exception{
        Database.init();
        HttpServer s=HttpServer.create(new InetSocketAddress(PORT),0);
        s.createContext("/api/auth",App::auth);
        s.createContext("/api/profile",App::profile);
        s.createContext("/api/family",App::family);
        s.createContext("/api/medicines",App::medicines);
        s.createContext("/api/history",App::history);
        s.createContext("/api/health",e->json(e,200,"{\"ok\":true}"));
        s.createContext("/",App::staticFile);
        s.start();
        System.out.println("MediMate running at http://localhost:"+PORT);
    }

    static void auth(HttpExchange e)throws IOException{
        try{
            String method=e.getRequestMethod(), path=e.getRequestURI().getPath();
            if("POST".equals(method)&&path.endsWith("/register")){
                Map<String,String>b=Json.parse(read(e));
                String name=b.get("name"),email=normalizeEmail(b.get("email")),phone=b.getOrDefault("phone",""),pass=b.get("password");
                if(blank(name)||blank(email)||blank(pass)||pass.length()<6){json(e,400,"{\"error\":\"Name, email and password (6+ characters) are required\"}");return;}
                if(!Database.query("SELECT id FROM users WHERE email=?",email).isEmpty()){json(e,409,"{\"error\":\"Email already registered\"}");return;}
                String hash=Hash.sha256(pass);
                long id=Database.insert("INSERT INTO users(name,email,phone,password_hash,photo,active) VALUES(?,?,?,?,?,1)",name,email,phone,hash,"");
                Database.insert("INSERT INTO family_members(user_id,name,relation) VALUES(?,?,?)",id,"Me","Self");
                String token=token();SESSIONS.put(token,id);cookie(e,token);
                json(e,201,userJson(id,name,email,phone,""));
            } else if("POST".equals(method)&&path.endsWith("/login")){
                Map<String,String>b=Json.parse(read(e));
                String email=normalizeEmail(b.get("email"));
                List<Map<String,Object>> rows=Database.query("SELECT id,name,email,phone,photo,password_hash FROM users WHERE email=?",email);
                if(rows.isEmpty()||!Hash.sha256(b.getOrDefault("password","")).equals(rows.get(0).get("password_hash"))){json(e,401,"{\"error\":\"Invalid email or password\"}");return;}
                Map<String,Object>u=rows.get(0);String t=token();SESSIONS.put(t,((Number)u.get("id")).longValue());cookie(e,t);
                json(e,200,userJson(((Number)u.get("id")).longValue(),u.get("name").toString(),u.get("email").toString(),String.valueOf(u.get("phone")),String.valueOf(u.get("photo"))));
            } else if("POST".equals(method)&&path.endsWith("/logout")){
                String c=e.getRequestHeaders().getFirst("Cookie");if(c!=null){String t=c.replaceFirst("(?i).*MEDIMATE_SESSION=([^;]+).*","$1");SESSIONS.remove(t);}
                e.getResponseHeaders().add("Set-Cookie","MEDIMATE_SESSION=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax");json(e,200,"{\"ok\":true}");
            } else if("GET".equals(method)&&path.endsWith("/me")){
                long uid=user(e); if(uid<0)return;
                List<Map<String,Object>>r=Database.query("SELECT id,name,email,phone,photo FROM users WHERE id=?",uid);
                json(e,200,r.isEmpty()?"null":Json.stringify(r.get(0)));
            } else method(e);
        }catch(Exception x){error(e,x);}
    }

    static void profile(HttpExchange e)throws IOException{
        try{
            long uid=user(e);if(uid<0)return;
            if("PUT".equals(e.getRequestMethod())){
                Map<String,String>b=Json.parse(read(e));
                Database.update("UPDATE users SET name=?,phone=?,photo=? WHERE id=?",b.get("name"),b.getOrDefault("phone",""),b.getOrDefault("photo",""),uid);
                json(e,200,"{\"ok\":true}");
            }else method(e);
        }catch(Exception x){error(e,x);}
    }

    static void family(HttpExchange e)throws IOException{
        try{long uid=user(e);if(uid<0)return;
            String m=e.getRequestMethod();
            if("GET".equals(m))json(e,200,Json.stringify(Database.query("SELECT id,name,relation,photo FROM family_members WHERE user_id=? ORDER BY id DESC",uid)));
            else if("POST".equals(m)){Map<String,String>b=Json.parse(read(e));long id=Database.insert("INSERT INTO family_members(user_id,name,relation,photo) VALUES(?,?,?,?)",uid,b.get("name"),b.getOrDefault("relation","Family"),b.getOrDefault("photo",""));json(e,201,"{\"id\":"+id+"}");}
            else if("DELETE".equals(m)){long id=lastId(e);Database.update("DELETE FROM family_members WHERE id=? AND user_id=?",id,uid);json(e,200,"{\"ok\":true}");}
            else method(e);
        }catch(Exception x){error(e,x);}
    }

    static void medicines(HttpExchange e)throws IOException{
        try{long uid=user(e);if(uid<0)return;String m=e.getRequestMethod();
            if("GET".equals(m)){
                String q="SELECT m.id,m.name,m.name_en,m.name_hi,m.name_kn,m.dosage,m.time,m.frequency,m.notes,m.ringtone,m.family_id,COALESCE(f.name,'Me') family_name FROM medicines m LEFT JOIN family_members f ON f.id=m.family_id WHERE m.user_id=? ORDER BY m.time";
                json(e,200,Json.stringify(Database.query(q,uid)));
            }else if("POST".equals(m)){
                Map<String,String>b=Json.parse(read(e));if(blank(b.get("name_en"))||blank(b.get("name_hi"))||blank(b.get("name_kn"))||blank(b.get("time"))){json(e,400,"{\"error\":\"Medicine names in English, Hindi, Kannada and time are required\"}");return;}
                Object familyId=familyRef(b.get("familyId"),uid); if(familyId==INVALID){json(e,400,"{\"error\":\"Invalid family member\"}");return;}
                long id=Database.insert("INSERT INTO medicines(user_id,name,name_en,name_hi,name_kn,dosage,time,frequency,notes,ringtone,family_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",uid,b.get("name_en"),b.get("name_en"),b.get("name_hi"),b.get("name_kn"),b.getOrDefault("dosage",""),b.get("time"),"Daily",b.getOrDefault("notes",""),b.getOrDefault("ringtone","soft-chime"),familyId);
                json(e,201,"{\"id\":"+id+"}");
            }else if("PUT".equals(m)){
                long id=lastId(e);Map<String,String>b=Json.parse(read(e));
                if(blank(b.get("name_en"))||blank(b.get("name_hi"))||blank(b.get("name_kn"))||blank(b.get("time"))){json(e,400,"{\"error\":\"Medicine names in English, Hindi, Kannada and time are required\"}");return;}
                Object familyId=familyRef(b.get("familyId"),uid); if(familyId==INVALID){json(e,400,"{\"error\":\"Invalid family member\"}");return;}
                Database.update("UPDATE medicines SET name=?,name_en=?,name_hi=?,name_kn=?,dosage=?,time=?,frequency=?,notes=?,ringtone=?,family_id=? WHERE id=? AND user_id=?",b.get("name_en"),b.get("name_en"),b.get("name_hi"),b.get("name_kn"),b.getOrDefault("dosage",""),b.get("time"),"Daily",b.getOrDefault("notes",""),b.getOrDefault("ringtone","soft-chime"),familyId,id,uid);json(e,200,"{\"ok\":true}");
            }else if("DELETE".equals(m)){long id=lastId(e);Database.update("DELETE FROM medicines WHERE id=? AND user_id=?",id,uid);json(e,200,"{\"ok\":true}");}
            else method(e);
        }catch(Exception x){error(e,x);}
    }

    static void history(HttpExchange e)throws IOException{
        try{long uid=user(e);if(uid<0)return;String m=e.getRequestMethod();
            if("GET".equals(m))json(e,200,Json.stringify(Database.query("SELECT h.id,h.medicine_id,h.taken_at,m.name_en AS name,m.name_hi,m.name_kn,m.dosage FROM dose_history h JOIN medicines m ON m.id=h.medicine_id WHERE h.user_id=? ORDER BY h.taken_at DESC LIMIT 200",uid)));
            else if("POST".equals(m)){Map<String,String>b=Json.parse(read(e));long mid=Long.parseLong(b.get("medicineId"));if(Database.query("SELECT id FROM medicines WHERE id=? AND user_id=?",mid,uid).isEmpty()){json(e,404,"{\"error\":\"Medicine not found\"}");return;}Database.update("INSERT INTO dose_history(user_id,medicine_id,taken_at) VALUES(?,?,NOW())",uid,mid);json(e,201,"{\"ok\":true}");}
            else method(e);
        }catch(Exception x){error(e,x);}
    }

    static long user(HttpExchange e)throws IOException{
        String c=e.getRequestHeaders().getFirst("Cookie");
        if(c==null){json(e,401,"{\"error\":\"Please login\"}");return -1;}
        String t=null;for(String p:c.split(";")){String z=p.trim();if(z.startsWith("MEDIMATE_SESSION="))t=z.substring(17);}
        Long id=t==null?null:SESSIONS.get(t);if(id==null){json(e,401,"{\"error\":\"Session expired. Please login again\"}");return -1;}return id;
    }
    static String normalizeEmail(String s){return s==null?"":s.trim().toLowerCase(java.util.Locale.ROOT);}
    static String token(){byte[]b=new byte[32];RNG.nextBytes(b);StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    static String userJson(long id,String n,String em,String ph,String photo){return "{\"id\":"+id+",\"name\":"+Json.str(n)+",\"email\":"+Json.str(em)+",\"phone\":"+Json.str(ph)+",\"photo\":"+Json.str(photo)+"}";}
    static final Object INVALID=new Object();
    static Object familyRef(String s,long uid)throws SQLException{
        if(blank(s)||"0".equals(s))return null;
        try{
            long id=Long.parseLong(s);
            return Database.query("SELECT id FROM family_members WHERE id=? AND user_id=?",id,uid).isEmpty()?INVALID:id;
        }catch(NumberFormatException e){return INVALID;}
    }
    static boolean blank(String s){return s==null||s.isBlank();}
    static long lastId(HttpExchange e){String[]a=e.getRequestURI().getPath().split("/");return Long.parseLong(a[a.length-1]);}
    static String read(HttpExchange e)throws IOException{return new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);}
    static void cookie(HttpExchange e,String t){e.getResponseHeaders().add("Set-Cookie","MEDIMATE_SESSION="+t+"; Path=/; HttpOnly; SameSite=Lax");}
    static void method(HttpExchange e)throws IOException{json(e,405,"{\"error\":\"Method not allowed\"}");}
    static void error(HttpExchange e,Exception x)throws IOException{x.printStackTrace();json(e,500,"{\"error\":"+Json.str(x.getMessage()==null?"Server error":x.getMessage())+"}");}
    static void json(HttpExchange e,int code,String s)throws IOException{byte[]b=s.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");e.sendResponseHeaders(code,b.length);try(OutputStream o=e.getResponseBody()){o.write(b);}}
    static void staticFile(HttpExchange e)throws IOException{
        String p=URLDecoder.decode(e.getRequestURI().getPath(),StandardCharsets.UTF_8);if(p.equals("/"))p="/index.html";Path f=STATIC.resolve(p.substring(1)).normalize();
        if(!f.startsWith(STATIC)||!Files.exists(f)||Files.isDirectory(f)){byte[]b="Not found".getBytes();e.sendResponseHeaders(404,b.length);e.getResponseBody().write(b);e.close();return;}
        byte[]b=Files.readAllBytes(f);e.getResponseHeaders().set("Content-Type",type(f.toString()));e.sendResponseHeaders(200,b.length);try(OutputStream o=e.getResponseBody()){o.write(b);}
    }
    static String type(String p){if(p.endsWith(".html"))return"text/html; charset=UTF-8";if(p.endsWith(".css"))return"text/css; charset=UTF-8";if(p.endsWith(".js"))return"application/javascript; charset=UTF-8";if(p.endsWith(".svg"))return"image/svg+xml";return"application/octet-stream";}

    static class Hash{static String sha256(String s)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[]b=d.digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder x=new StringBuilder();for(byte z:b)x.append(String.format("%02x",z));return x.toString();}}
    static class Database{
        static final String HOST="jdbc:mysql://localhost:3306/?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        static final String URL="jdbc:mysql://localhost:3306/medicine_reminder?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        static final String USER=System.getenv().getOrDefault("MED_DB_USER","root"),PASS=System.getenv().getOrDefault("MED_DB_PASSWORD","root");
        static Connection c()throws SQLException{return DriverManager.getConnection(URL,USER,PASS);}
        static void init()throws SQLException{
            // Create the database first so a fresh installation can start cleanly.
            try(Connection c=DriverManager.getConnection(HOST,USER,PASS);Statement s=c.createStatement()){
                s.executeUpdate("CREATE DATABASE IF NOT EXISTS medicine_reminder");
            }
            try(Connection c=c();Statement s=c.createStatement()){
                // Create the current schema when tables do not exist.
                s.executeUpdate("CREATE TABLE IF NOT EXISTS users(id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(100) NOT NULL,email VARCHAR(160) UNIQUE NOT NULL,phone VARCHAR(30),password_hash VARCHAR(64) NOT NULL,photo MEDIUMTEXT,active TINYINT(1) NOT NULL DEFAULT 1)");

                // Migrate old user schemas without requiring the user to delete their database.
                if(hasColumn(c,"users","full_name") && !hasColumn(c,"users","name")){
                    s.executeUpdate("ALTER TABLE users CHANGE COLUMN full_name name VARCHAR(100) NOT NULL");
                }else if(hasColumn(c,"users","full_name") && hasColumn(c,"users","name")){
                    s.executeUpdate("UPDATE users SET name=COALESCE(NULLIF(TRIM(name),''),full_name) WHERE (name IS NULL OR TRIM(name)='') AND full_name IS NOT NULL");
                    try{s.executeUpdate("ALTER TABLE users MODIFY COLUMN full_name VARCHAR(100) NULL");}catch(SQLException ignored){}
                }
                ensureColumn(c,s,"users","name","VARCHAR(100) NULL");
                ensureColumn(c,s,"users","phone","VARCHAR(30) NULL");
                ensureColumn(c,s,"users","password_hash","VARCHAR(64) NULL");
                ensureColumn(c,s,"users","photo","MEDIUMTEXT NULL");
                ensureColumn(c,s,"users","active","TINYINT(1) NOT NULL DEFAULT 1");
                s.executeUpdate("UPDATE users SET name=COALESCE(NULLIF(TRIM(name),''),'User') WHERE name IS NULL OR TRIM(name)=''");
                s.executeUpdate("UPDATE users SET password_hash=COALESCE(password_hash,'') WHERE password_hash IS NULL");
                s.executeUpdate("UPDATE users SET active=1 WHERE active IS NULL");
                try{s.executeUpdate("ALTER TABLE users MODIFY COLUMN name VARCHAR(100) NOT NULL");}catch(SQLException ignored){}
                try{s.executeUpdate("ALTER TABLE users MODIFY COLUMN password_hash VARCHAR(64) NOT NULL");}catch(SQLException ignored){}
                try{s.executeUpdate("ALTER TABLE users MODIFY COLUMN active TINYINT(1) NOT NULL DEFAULT 1");}catch(SQLException ignored){}

                s.executeUpdate("CREATE TABLE IF NOT EXISTS family_members(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,name VARCHAR(100) NOT NULL,relation VARCHAR(50),photo MEDIUMTEXT,FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE)");
                ensureColumn(c,s,"family_members","relation","VARCHAR(50) NULL");
                ensureColumn(c,s,"family_members","photo","MEDIUMTEXT NULL");

                s.executeUpdate("CREATE TABLE IF NOT EXISTS medicines(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,name VARCHAR(150) NOT NULL,name_en VARCHAR(150),name_hi VARCHAR(150),name_kn VARCHAR(150),dosage VARCHAR(100),time VARCHAR(5) NOT NULL,frequency VARCHAR(30),notes VARCHAR(500),ringtone VARCHAR(100),active TINYINT(1) NOT NULL DEFAULT 1,family_id BIGINT NULL,FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,FOREIGN KEY(family_id) REFERENCES family_members(id) ON DELETE SET NULL)");
                ensureColumn(c,s,"medicines","name","VARCHAR(150) NULL");
                ensureColumn(c,s,"medicines","name_en","VARCHAR(150) NULL");
                ensureColumn(c,s,"medicines","name_hi","VARCHAR(150) NULL");
                ensureColumn(c,s,"medicines","name_kn","VARCHAR(150) NULL");
                ensureColumn(c,s,"medicines","dosage","VARCHAR(100) NULL");
                ensureColumn(c,s,"medicines","time","VARCHAR(5) NULL");
                ensureColumn(c,s,"medicines","frequency","VARCHAR(30) NULL");
                ensureColumn(c,s,"medicines","notes","VARCHAR(500) NULL");
                ensureColumn(c,s,"medicines","ringtone","VARCHAR(100) NULL");
                ensureColumn(c,s,"medicines","active","TINYINT(1) NOT NULL DEFAULT 1");
                ensureColumn(c,s,"medicines","family_id","BIGINT NULL");

                // Legacy database compatibility: some older releases used `times`
                // instead of `time`. Keep the old column if it exists, but make it
                // harmless for new inserts. If `time` does not exist, migrate it.
                if(hasColumn(c,"medicines","times")){
                    if(!hasColumn(c,"medicines","time")){
                        s.executeUpdate("ALTER TABLE medicines CHANGE COLUMN times time VARCHAR(5) NULL");
                    }else{
                        try{s.executeUpdate("UPDATE medicines SET time=COALESCE(NULLIF(time,''),NULLIF(times,''),'08:00')");}catch(SQLException ignored){}
                        try{s.executeUpdate("ALTER TABLE medicines MODIFY COLUMN times VARCHAR(5) NULL DEFAULT NULL");}catch(SQLException ignored){}
                    }
                }

                s.executeUpdate("UPDATE medicines SET active=1 WHERE active IS NULL");
                s.executeUpdate("UPDATE medicines SET name=COALESCE(NULLIF(name,''),name_en,name_hi,name_kn,'Medicine') WHERE name IS NULL OR name=''");
                s.executeUpdate("UPDATE medicines SET name_en=COALESCE(NULLIF(name_en,''),name),name_hi=COALESCE(NULLIF(name_hi,''),name),name_kn=COALESCE(NULLIF(name_kn,''),name),frequency=COALESCE(NULLIF(frequency,''),'Daily'),ringtone=COALESCE(NULLIF(ringtone,''),'soft-chime'),time=COALESCE(NULLIF(time,''),'08:00')");
                try{s.executeUpdate("ALTER TABLE medicines MODIFY COLUMN name VARCHAR(150) NOT NULL");}catch(SQLException ignored){}
                try{s.executeUpdate("ALTER TABLE medicines MODIFY COLUMN time VARCHAR(5) NOT NULL");}catch(SQLException ignored){}
                try{s.executeUpdate("ALTER TABLE medicines MODIFY COLUMN active TINYINT(1) NOT NULL DEFAULT 1");}catch(SQLException ignored){}

                s.executeUpdate("CREATE TABLE IF NOT EXISTS dose_history(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,medicine_id BIGINT NOT NULL,taken_at DATETIME NOT NULL,FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,FOREIGN KEY(medicine_id) REFERENCES medicines(id) ON DELETE CASCADE)");

                // Finally repair any extra legacy NOT NULL columns that have no
                // default. Canonical columns are intentionally left strict because
                // the application supplies them on every insert. This prevents old
                // schema-only fields such as `times` from breaking new records.
                repairLegacyRequiredColumns(c,s,"users",new String[]{"id","name","email","password_hash"});
                repairLegacyRequiredColumns(c,s,"family_members",new String[]{"id","user_id","name"});
                repairLegacyRequiredColumns(c,s,"medicines",new String[]{"id","user_id","name","time"});
                repairLegacyRequiredColumns(c,s,"dose_history",new String[]{"id","user_id","medicine_id","taken_at"});
            }
        }
        static boolean hasColumn(Connection c,String table,String column)throws SQLException{
            try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?")){
                p.setString(1,table);p.setString(2,column);try(ResultSet r=p.executeQuery()){r.next();return r.getInt(1)>0;}
            }
        }
        static void ensureColumn(Connection c,Statement s,String table,String column,String definition)throws SQLException{
            if(!hasColumn(c,table,column))s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);
        }
        static void repairLegacyRequiredColumns(Connection c,Statement s,String table,String[] keep)throws SQLException{
            String sql="SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
            try(PreparedStatement p=c.prepareStatement(sql)){
                p.setString(1,table);
                try(ResultSet r=p.executeQuery()){
                    while(r.next()){
                        String col=r.getString("COLUMN_NAME");
                        boolean required="NO".equalsIgnoreCase(r.getString("IS_NULLABLE"));
                        boolean noDefault=r.getString("COLUMN_DEFAULT")==null;
                        String extra=r.getString("EXTRA");
                        if(!required||!noDefault||(extra!=null&&extra.toLowerCase().contains("auto_increment"))||contains(keep,col))continue;
                        // Legacy-only columns should not block current application inserts.
                        try{s.executeUpdate("ALTER TABLE "+table+" MODIFY COLUMN "+col+" "+legacyNullableDefinition(r.getString("COLUMN_TYPE")));}catch(SQLException ignored){}
                    }
                }
            }
        }
        static boolean contains(String[] a,String v){for(String x:a)if(x.equalsIgnoreCase(v))return true;return false;}
        static String legacyNullableDefinition(String type){
            if(type==null)return "VARCHAR(255) NULL";
            switch(type.toLowerCase()){
                case "tinyint": case "smallint": case "mediumint": case "int": case "integer": case "bigint": return type+" NULL DEFAULT NULL";
                case "decimal": case "numeric": case "float": case "double": return type+" NULL DEFAULT NULL";
                case "date": case "datetime": case "timestamp": case "time": return type+" NULL DEFAULT NULL";
                case "text": case "mediumtext": case "longtext": return type+" NULL";
                default: return type+" NULL DEFAULT NULL";
            }
        }
        static List<Map<String,Object>>query(String q,Object...a)throws SQLException{try(Connection c=c();PreparedStatement p=c.prepareStatement(q)){for(int i=0;i<a.length;i++)p.setObject(i+1,a[i]);try(ResultSet r=p.executeQuery()){List<Map<String,Object>>z=new ArrayList<>();ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object>row=new LinkedHashMap<>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getObject(i));z.add(row);}return z;}}}
        static long insert(String q,Object...a)throws SQLException{try(Connection c=c();PreparedStatement p=c.prepareStatement(q,Statement.RETURN_GENERATED_KEYS)){for(int i=0;i<a.length;i++)p.setObject(i+1,a[i]);p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();return r.getLong(1);}}}
        static int update(String q,Object...a)throws SQLException{try(Connection c=c();PreparedStatement p=c.prepareStatement(q)){for(int i=0;i<a.length;i++)p.setObject(i+1,a[i]);return p.executeUpdate();}}
    }
    static class Json{
        static String str(String s){if(s==null)return"\"\"";return"\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
        static String stringify(Object o){if(o==null)return"null";if(o instanceof String)return str((String)o);if(o instanceof Number||o instanceof Boolean)return o.toString();if(o instanceof Map){StringBuilder b=new StringBuilder("{");boolean f=true;for(Object k:((Map<?,?>)o).keySet()){if(!f)b.append(',');f=false;b.append(str(k.toString())).append(':').append(stringify(((Map<?,?>)o).get(k)));}return b.append('}').toString();}if(o instanceof Iterable){StringBuilder b=new StringBuilder("[");boolean f=true;for(Object x:(Iterable<?>)o){if(!f)b.append(',');f=false;b.append(stringify(x));}return b.append(']').toString();}return str(o.toString());}
        static Map<String,String>parse(String j){Map<String,String>m=new LinkedHashMap<>();if(j==null)return m;String s=j.trim();if(s.startsWith("{"))s=s.substring(1,s.length()-1);List<String>p=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false,e=false;for(char c:s.toCharArray()){if(e){b.append(c);e=false;continue;}if(c=='\\'&&q){b.append(c);e=true;continue;}if(c=='"'){q=!q;b.append(c);continue;}if(c==','&&!q){p.add(b.toString());b.setLength(0);}else b.append(c);}if(b.length()>0)p.add(b.toString());for(String x:p){int i=x.indexOf(':');if(i<0)continue;m.put(un(x.substring(0,i)),un(x.substring(i+1)));}return m;}
        static String un(String s){s=s.trim();if(s.equals("null"))return"";if(s.startsWith("\"")&&s.endsWith("\""))return s.substring(1,s.length()-1).replace("\\\"","\"").replace("\\\\","\\").replace("\\n","\n").replace("\\r","\r");return s;}
    }
}
