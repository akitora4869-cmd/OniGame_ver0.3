package jp.muimi.onigame;
public enum PlayerSkill {
    SPRINT("疾走", 25), INVISIBLE("隠形", 40), SMOKE("煙幕", 35), ONI_STRIKE("破鬼撃", 45);
    public final String display; public final int cooldown;
    PlayerSkill(String display, int cooldown) { this.display=display; this.cooldown=cooldown; }
    public static PlayerSkill parse(String s) {
        if (s == null) return null;
        return switch(s.toLowerCase()) { case "sprint", "疾走" -> SPRINT; case "invisible", "隠形" -> INVISIBLE; case "smoke", "煙幕" -> SMOKE; case "strike", "破鬼撃" -> ONI_STRIKE; default -> null; };
    }
}
