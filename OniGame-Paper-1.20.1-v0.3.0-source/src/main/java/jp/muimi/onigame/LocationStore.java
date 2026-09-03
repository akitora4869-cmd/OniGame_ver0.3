package jp.muimi.onigame;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

public final class LocationStore {
    private LocationStore() {}
    public static void set(FileConfiguration c, String path, Location l) {
        c.set(path+".world", l.getWorld().getName()); c.set(path+".x",l.getX()); c.set(path+".y",l.getY()); c.set(path+".z",l.getZ());
        c.set(path+".yaw",l.getYaw()); c.set(path+".pitch",l.getPitch());
    }
    public static Location get(FileConfiguration c, String path) {
        World w=Bukkit.getWorld(c.getString(path+".world", "")); if(w==null)return null;
        return new Location(w,c.getDouble(path+".x"),c.getDouble(path+".y"),c.getDouble(path+".z"),(float)c.getDouble(path+".yaw"),(float)c.getDouble(path+".pitch"));
    }
    public static String encode(Location l){return l.getWorld().getName()+","+l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ();}
    public static Location decode(String s){String[] p=s.split(","); if(p.length!=4)return null; World w=Bukkit.getWorld(p[0]); if(w==null)return null; try{return new Location(w,Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));}catch(Exception e){return null;}}
}
