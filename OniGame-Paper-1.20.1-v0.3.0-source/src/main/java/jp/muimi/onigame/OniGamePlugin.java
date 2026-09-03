package jp.muimi.onigame;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;

import java.util.*;

public final class OniGamePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private GameState state=GameState.WAITING;
    private final Set<UUID> participants=new HashSet<>(), players=new HashSet<>(), escaped=new HashSet<>(), dead=new HashSet<>();
    private final Map<UUID,PlayerSkill> selectedSkill=new HashMap<>();
    private final Map<String,Long> cooldowns=new HashMap<>();
    private final Map<String,Integer> heartHp=new HashMap<>();
    private UUID oni; private OniType oniType=OniType.DAKKO; private int brokenHearts, totalHearts, secondsLeft; private BukkitTask ticker, sidebarTicker;
    private NamespacedKey actionKey;

    @Override public void onEnable(){saveDefaultConfig(); actionKey=new NamespacedKey(this,"action"); getServer().getPluginManager().registerEvents(this,this); PluginCommand c=getCommand("onigame"); c.setExecutor(this); c.setTabCompleter(this); sidebarTicker=Bukkit.getScheduler().runTaskTimer(this,this::updateSidebars,1,20); getLogger().info("鬼げぇむ v0.3.0 enabled");}
    @Override public void onDisable(){if(ticker!=null)ticker.cancel();if(sidebarTicker!=null)sidebarTicker.cancel();}

    private String cc(String s){return ChatColor.translateAlternateColorCodes('&',s);}
    private void msg(CommandSender s,String m){s.sendMessage(cc("&8[&c鬼げぇむ&8] &f"+m));}
    private void all(String m){Bukkit.broadcastMessage(cc("&8[&c鬼げぇむ&8] &f"+m));}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a){
        if(a.length==0||a[0].equalsIgnoreCase("help")){help(sender);return true;}
        if(a[0].equalsIgnoreCase("join")&&sender instanceof Player p){if(state!=GameState.WAITING){msg(p,"試合中は参加できません。");return true;} participants.add(p.getUniqueId()); selectedSkill.putIfAbsent(p.getUniqueId(),PlayerSkill.SPRINT); msg(p,"参加しました。現在 &e"+participants.size()+"人");return true;}
        if(a[0].equalsIgnoreCase("leave")&&sender instanceof Player p){participants.remove(p.getUniqueId());selectedSkill.remove(p.getUniqueId());msg(p,"参加を取り消しました。");return true;}
        if(a[0].equalsIgnoreCase("skill")&&sender instanceof Player p){PlayerSkill sk=a.length>1?PlayerSkill.parse(a[1]):null;if(sk==null){msg(p,"sprint / invisible / smoke / strike から選択してください。");return true;}selectedSkill.put(p.getUniqueId(),sk);msg(p,"スキルを &b"+sk.display+" &fに設定しました。");return true;}
        if(!sender.hasPermission("onigame.admin")){msg(sender,"権限がありません。");return true;}
        if(a[0].equalsIgnoreCase("gmbook")&&sender instanceof Player p){p.getInventory().addItem(createGmBook());msg(p,"&6GM操作本 &fを渡しました。");return true;}
        if(a[0].equalsIgnoreCase("heartmode")&&a.length>1){String mode=a[1].toLowerCase();if(!mode.equals("random")&&!mode.equals("manual")){msg(sender,"random または manual を指定してください。");return true;}getConfig().set("heart-placement-mode",mode);saveConfig();msg(sender,"心臓配置を &e"+(mode.equals("random")?"ランダム":"手動マーカー")+" &fに設定しました。");return true;}
        if(a[0].equalsIgnoreCase("marker")&&sender instanceof Player p){p.getInventory().addItem(createHeartMarker());msg(p,"心臓地点マーカーを渡しました。設置すると自動で透明になります。");return true;}
        if(a[0].equalsIgnoreCase("markers")){int count=scanHeartMarkers(true).size();msg(sender,"有効な心臓地点マーカー: &e"+count+"個");return true;}
        if(a[0].equalsIgnoreCase("set")&&sender instanceof Player p&&a.length>1){
            switch(a[1].toLowerCase()){
                case "lobby" -> LocationStore.set(getConfig(),"locations.lobby",p.getLocation());
                case "player" -> LocationStore.set(getConfig(),"locations.player-spawn",p.getLocation());
                case "oni" -> LocationStore.set(getConfig(),"locations.oni-spawn",p.getLocation());
                case "exit" -> LocationStore.set(getConfig(),"locations.exit",p.getLocation());
                default -> {msg(p,"lobby / player / oni / exit を指定してください。");return true;}
            } saveConfig();msg(p,a[1]+"地点を設定しました。");return true;
        }
        if(a[0].equalsIgnoreCase("heart")&&sender instanceof Player p){List<String> list=getConfig().getStringList("locations.hearts");String encoded=LocationStore.encode(p.getLocation());if(list.contains(encoded)){list.remove(encoded);msg(p,"この心臓候補を削除しました。");}else{list.add(encoded);msg(p,"心臓候補を追加しました（"+list.size()+"件）。");}getConfig().set("locations.hearts",list);saveConfig();return true;}
        if(a[0].equalsIgnoreCase("start")){OniType type=a.length>1?OniType.parse(a[1]):null;start(type);return true;}
        if(a[0].equalsIgnoreCase("stop")){end(false,"管理者が試合を終了しました");return true;}
        if(a[0].equalsIgnoreCase("status")){String mode=getConfig().getString("heart-placement-mode","random");msg(sender,"状態: "+state+" / 参加: "+participants.size()+" / 心臓: "+brokenHearts+"/"+totalHearts+" / 配置: "+mode);return true;}
        help(sender);return true;
    }
    private void help(CommandSender s){s.sendMessage(cc("&c&l鬼げぇむ &7- commands\n&e/onigame join|leave &7参加・退出\n&e/onigame skill <sprint|invisible|smoke|strike>\n&e/onigame gmbook &7GM操作本を取得\n&e/onigame heartmode <random|manual>\n&e/onigame marker / markers &7手動配置マーカー\n&e/onigame set <lobby|player|oni|exit> &7[管理]\n&e/onigame heart &7ランダム候補を追加/削除\n&e/onigame start [dakko|kishin] / stop / status"));}

    private void start(OniType forced){
        if(state!=GameState.WAITING){all("すでに試合中です。");return;}
        participants.removeIf(id->Bukkit.getPlayer(id)==null);
        if(participants.size()<getConfig().getInt("minimum-players",2)){all("参加人数が足りません。/onigame join で参加してください。");return;}
        Location ps=LocationStore.get(getConfig(),"locations.player-spawn"), os=LocationStore.get(getConfig(),"locations.oni-spawn"), ex=LocationStore.get(getConfig(),"locations.exit");
        String placementMode=getConfig().getString("heart-placement-mode","random");
        List<Location> hearts=placementMode.equalsIgnoreCase("manual")?scanHeartMarkers(true):selectRandomHearts();
        if(ps==null||os==null||ex==null||hearts.isEmpty()){all("開始地点・鬼地点・出口・現在の配置方式に対応する心臓地点を設定してください。");return;}
        resetRuntime(); List<UUID> ids=new ArrayList<>(participants); Collections.shuffle(ids); oni=ids.remove(0); players.addAll(ids); oniType=forced!=null?forced:OniType.values()[new Random().nextInt(2)];
        Material heartMat=Material.matchMaterial(getConfig().getString("heart-material","CRYING_OBSIDIAN")); if(heartMat==null)heartMat=Material.CRYING_OBSIDIAN;
        for(Location l:hearts){l.getBlock().setType(heartMat);heartHp.put(LocationStore.encode(l),getConfig().getInt("heart-max-health",20));}
        totalHearts=heartHp.size();secondsLeft=getConfig().getInt("game-seconds",1200);state=GameState.RUNNING;
        Player op=Bukkit.getPlayer(oni); setupOni(op,os); for(UUID id:players)setupPlayer(Bukkit.getPlayer(id),ps);
        all("&4&l鬼げぇむ 開始！ &f鬼は &c"+op.getName()+" &7(&c"+oniType.display+"&7) &fです。心臓を破壊し、討伐または脱出せよ。");
        ticker=Bukkit.getScheduler().runTaskTimer(this,this::tick,20,20);
    }
    private void resetRuntime(){players.clear();escaped.clear();dead.clear();cooldowns.clear();heartHp.clear();brokenHearts=0;if(ticker!=null)ticker.cancel();}
    private void common(Player p){p.getInventory().clear();p.getActivePotionEffects().forEach(e->p.removePotionEffect(e.getType()));p.setGameMode(GameMode.ADVENTURE);p.setHealth(20);p.setFoodLevel(20);p.setFireTicks(0);}
    private void setupPlayer(Player p,Location at){common(p);p.teleport(at);PlayerSkill sk=selectedSkill.getOrDefault(p.getUniqueId(),PlayerSkill.SPRINT);p.getInventory().setItem(0,item(skillMaterial(sk),"&b&l"+sk.display,"skill:"+sk.name()));p.getInventory().setItem(8,item(Material.COMPASS,"&d心臓探知機","tracker"));}
    private void setupOni(Player p,Location at){common(p);p.teleport(at);p.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(60);p.setHealth(60);p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE,Integer.MAX_VALUE,0,false,false));p.getInventory().setItem(0,item(Material.NETHERITE_SWORD,"&4&l鬼の爪","oni_weapon"));if(oniType==OniType.DAKKO){equipDakkoArmor(p);p.getInventory().setItem(1,item(Material.ENDER_PEARL,"&d狐渡り","dakko_tp"));p.getInventory().setItem(2,item(Material.FOX_SPAWN_EGG,"&6分霊","dakko_clone"));}else{p.getInventory().setItem(1,item(Material.FIREWORK_ROCKET,"&c鬼突","kishin_charge"));p.getInventory().setItem(2,item(Material.IRON_AXE,"&4地砕","kishin_slam"));}}
    private Material skillMaterial(PlayerSkill s){return switch(s){case SPRINT->Material.FEATHER;case INVISIBLE->Material.GLASS_BOTTLE;case SMOKE->Material.INK_SAC;case ONI_STRIKE->Material.BLAZE_ROD;};}
    private ItemStack item(Material m,String name,String action){ItemStack i=new ItemStack(m);ItemMeta im=i.getItemMeta();im.setDisplayName(cc(name));im.getPersistentDataContainer().set(actionKey,PersistentDataType.STRING,action);i.setItemMeta(im);return i;}
    private ItemStack createHeartMarker(){ItemStack i=item(Material.ARMOR_STAND,"&4&l鬼の心臓地点マーカー","heart_marker");ItemMeta m=i.getItemMeta();m.setLore(List.of(cc("&7設置地点に心臓を生成します。"),cc("&7設置後は透明・固定・無敵になります。")));i.setItemMeta(m);return i;}
    private void equipDakkoArmor(Player p){
        p.getInventory().setHelmet(oniArmor(Material.CHAINMAIL_HELMET,"&5堕狐の面"));
        p.getInventory().setChestplate(oniArmor(Material.CHAINMAIL_CHESTPLATE,"&5堕狐の装束"));
        p.getInventory().setLeggings(oniArmor(Material.CHAINMAIL_LEGGINGS,"&5堕狐の袴"));
        p.getInventory().setBoots(oniArmor(Material.CHAINMAIL_BOOTS,"&5堕狐の足袋"));
    }
    private ItemStack oniArmor(Material material,String name){ItemStack i=item(material,name,"dakko_armor");ItemMeta m=i.getItemMeta();m.setUnbreakable(true);m.setLore(List.of(cc("&8堕狐専用装備")));i.setItemMeta(m);return i;}
    private boolean isDakkoArmor(ItemStack i){if(i==null||!i.hasItemMeta())return false;return "dakko_armor".equals(i.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING));}
    private ItemStack createGmBook(){
        ItemStack book=new ItemStack(Material.WRITTEN_BOOK);BookMeta m=(BookMeta)book.getItemMeta();
        m.title(Component.text("鬼げぇむ GM操作本",NamedTextColor.DARK_RED));m.author(Component.text("鬼げぇむ"));
        Component page1=Component.text("【心臓の配置方式】\n\n",NamedTextColor.DARK_RED)
                .append(button("▶ ランダム配置\n",NamedTextColor.GREEN,"/og heartmode random"))
                .append(Component.text("登録候補から設定個数を抽選\n\n",NamedTextColor.GRAY))
                .append(button("▶ 手動配置\n",NamedTextColor.GOLD,"/og heartmode manual"))
                .append(Component.text("専用マーカーの全地点に生成",NamedTextColor.GRAY));
        Component page2=Component.text("【心臓マーカー】\n\n",NamedTextColor.DARK_RED)
                .append(button("▶ マーカーを受け取る\n",NamedTextColor.GOLD,"/og marker"))
                .append(button("▶ マーカー数を確認\n",NamedTextColor.AQUA,"/og markers"))
                .append(Component.text("\n設置したマーカーは自動で透明になります。",NamedTextColor.GRAY));
        Component page3=Component.text("【ゲーム操作】\n\n",NamedTextColor.DARK_RED)
                .append(button("▶ 堕狐で開始\n",NamedTextColor.LIGHT_PURPLE,"/og start dakko"))
                .append(button("▶ 鬼神で開始\n",NamedTextColor.RED,"/og start kishin"))
                .append(button("▶ 状態を確認\n",NamedTextColor.AQUA,"/og status"))
                .append(button("▶ ゲームを停止",NamedTextColor.DARK_RED,"/og stop"));
        m.pages(List.of(page1,page2,page3));book.setItemMeta(m);return book;
    }
    private Component button(String text,NamedTextColor color,String command){return Component.text(text,color).clickEvent(ClickEvent.runCommand(command));}
    private List<Location> selectRandomHearts(){List<Location> locations=new ArrayList<>(getConfig().getStringList("locations.hearts").stream().map(LocationStore::decode).filter(Objects::nonNull).toList());Collections.shuffle(locations);int count=Math.min(Math.max(1,getConfig().getInt("random-heart-count",5)),locations.size());return new ArrayList<>(locations.subList(0,count));}
    private List<Location> scanHeartMarkers(boolean hide){
        String markerName=getConfig().getString("heart-marker-name","鬼の心臓地点");Map<String,Location> unique=new LinkedHashMap<>();
        for(World world:Bukkit.getWorlds())for(ArmorStand stand:world.getEntitiesByClass(ArmorStand.class))if(markerName.equals(stand.getCustomName())){if(hide)configureMarker(stand);Location l=stand.getLocation().getBlock().getLocation();unique.put(LocationStore.encode(l),l);}
        return new ArrayList<>(unique.values());
    }
    private void configureMarker(ArmorStand stand){stand.setCustomName(getConfig().getString("heart-marker-name","鬼の心臓地点"));stand.setCustomNameVisible(false);stand.setVisible(false);stand.setGravity(false);stand.setInvulnerable(true);stand.setMarker(true);stand.setPersistent(true);}

    private void tick(){if(state!=GameState.RUNNING)return;secondsLeft--;for(UUID id:participants){Player p=Bukkit.getPlayer(id);if(p==null)continue;p.sendActionBar(cc("&c心臓 "+brokenHearts+"/&f"+totalHearts+"  &7|  &e残り "+(secondsLeft/60)+":"+String.format("%02d",secondsLeft%60)));}checkExit();if(secondsLeft<=0)end(true,"時間切れ――鬼の勝利");}
    private void updateSidebars(){if(state==GameState.RUNNING||state==GameState.ENDING)updateGameSidebar();else updateLobbySidebar();}
    private void updateLobbySidebar(){
        for(Player viewer:Bukkit.getOnlinePlayers()){
            Scoreboard board=Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o=board.registerNewObjective("onigame","dummy",cc("&4&l-鬼げぇむ-"));o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.getScore(cc("&7──────────")).setScore(3);
            o.getScore(cc("&fオンライン数")).setScore(2);
            o.getScore(cc("&a"+Bukkit.getOnlinePlayers().size()+" &7人")).setScore(1);
            viewer.setScoreboard(board);
        }
    }
    private void updateGameSidebar(){
        Scoreboard board=Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o=board.registerNewObjective("onigame","dummy",cc("&4&l-鬼げぇむ-"));o.setDisplaySlot(DisplaySlot.SIDEBAR);
        int score=15;
        o.getScore(cc("&e残り時間 &f"+(Math.max(0,secondsLeft)/60)+":"+String.format("%02d",Math.max(0,secondsLeft)%60))).setScore(score--);
        o.getScore(cc("&c心臓 &f"+brokenHearts+"&7/&f"+totalHearts)).setScore(score--);
        o.getScore(cc("&8──────────")).setScore(score--);
        List<UUID> shown=new ArrayList<>(players);shown.sort(Comparator.comparing(id->{Player p=Bukkit.getPlayer(id);return p==null?"":p.getName();}));
        for(UUID id:shown){
            if(score<=0)break;
            Player target=Bukkit.getPlayer(id);String name=target!=null?target.getName():"退出者";String icon;
            if(escaped.contains(id))icon="&f◆";
            else if(dead.contains(id)||target==null)icon="&c✖";
            else {double max=Objects.requireNonNull(target.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue();icon=target.getHealth()<=max*.30?"&e▲":"&a●";}
            o.getScore(cc(icon+" &f"+trimName(name))).setScore(score--);
        }
        if(score>0)o.getScore(cc("&8──────────&0")).setScore(score--);
        if(score>0)o.getScore(cc("&a● 通常  &e▲ 瀕死")).setScore(score--);
        if(score>0)o.getScore(cc("&c✖ 脱落  &f◆ 脱出")).setScore(score);
        for(Player viewer:Bukkit.getOnlinePlayers())viewer.setScoreboard(board);
    }
    private String trimName(String name){return name.length()>12?name.substring(0,12):name;}
    private void checkExit(){if(brokenHearts<totalHearts)return;Location exit=LocationStore.get(getConfig(),"locations.exit");for(UUID id:new HashSet<>(players)){if(dead.contains(id)||escaped.contains(id))continue;Player p=Bukkit.getPlayer(id);if(p!=null&&p.getWorld().equals(exit.getWorld())&&p.getLocation().distanceSquared(exit)<=Math.pow(getConfig().getDouble("exit-radius",3),2)){escaped.add(id);p.setGameMode(GameMode.SPECTATOR);all("&b"+p.getName()+" &fが脱出しました！");}}checkPlayerOutcome();}
    private void checkPlayerOutcome(){if(state!=GameState.RUNNING)return;long active=players.stream().filter(id->!dead.contains(id)&&!escaped.contains(id)).count();if(active==0){if(!escaped.isEmpty())end(false,"&b生 還――ぷれいやーの勝利");else end(true,"&4全 滅――鬼の勝利");}}
    private void end(boolean oniWin,String reason){if(state==GameState.WAITING)return;state=GameState.ENDING;if(ticker!=null)ticker.cancel();all((oniWin?"&4&l":"&b&l")+reason);Bukkit.getScheduler().runTaskLater(this,()->{Location lobby=LocationStore.get(getConfig(),"locations.lobby");for(UUID id:participants){Player p=Bukkit.getPlayer(id);if(p==null)continue;p.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);common(p);if(lobby!=null)p.teleport(lobby);}state=GameState.WAITING;resetRuntime();},100);}

    @EventHandler public void onBreak(BlockBreakEvent e){if(state!=GameState.RUNNING)return;String key=LocationStore.encode(e.getBlock().getLocation());if(!heartHp.containsKey(key))return;e.setCancelled(true);if(!players.contains(e.getPlayer().getUniqueId())||dead.contains(e.getPlayer().getUniqueId()))return;int damage=e.getPlayer().getInventory().getItemInMainHand().getType().name().contains("PICKAXE")?4:1;int hp=heartHp.get(key)-damage;if(hp>0){heartHp.put(key,hp);e.getBlock().getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,e.getBlock().getLocation().add(.5,.5,.5),4);e.getPlayer().sendActionBar(cc("&c心臓耐久: "+hp));return;}heartHp.remove(key);e.getBlock().setType(Material.AIR);brokenHearts++;all("&c鬼の心臓が破壊された！ &f("+brokenHearts+"/"+totalHearts+")");weakenOni();if(brokenHearts==totalHearts)all("&4鬼の不死性が消滅した。脱出口が開いた！");}
    @EventHandler public void onEntityPlace(EntityPlaceEvent e){
        if(!(e.getEntity() instanceof ArmorStand stand)||e.getPlayer()==null)return;
        ItemStack held=e.getHand()==EquipmentSlot.HAND?e.getPlayer().getInventory().getItemInMainHand():e.getPlayer().getInventory().getItemInOffHand();
        if(!held.hasItemMeta())return;String action=held.getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);if(!"heart_marker".equals(action))return;
        configureMarker(stand);msg(e.getPlayer(),"心臓地点マーカーを設置しました。ゲーム開始時、この位置に心臓が生成されます。");
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e){if(state!=GameState.RUNNING||oni==null||!e.getWhoClicked().getUniqueId().equals(oni))return;if(e.getSlotType()==InventoryType.SlotType.ARMOR||isDakkoArmor(e.getCurrentItem())||isDakkoArmor(e.getCursor()))e.setCancelled(true);}
    @EventHandler public void onDrop(PlayerDropItemEvent e){if(state==GameState.RUNNING&&e.getPlayer().getUniqueId().equals(oni)&&isDakkoArmor(e.getItemDrop().getItemStack()))e.setCancelled(true);}
    private void weakenOni(){Player p=Bukkit.getPlayer(oni);if(p==null)return;double max=Math.max(30,60-(brokenHearts*6));p.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(max);if(p.getHealth()>max)p.setHealth(max);p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,120,Math.min(3,brokenHearts-1),false,true));if(brokenHearts==3){if(oniType==OniType.DAKKO)p.getInventory().setItem(2,null);else p.getInventory().setItem(2,null);msg(p,"心臓の喪失により固有能力の一つが封印されました。");}}

    @EventHandler public void onInteract(PlayerInteractEvent e){if(state!=GameState.RUNNING||e.getItem()==null||!e.getAction().isRightClick())return;String action=e.getItem().getItemMeta().getPersistentDataContainer().get(actionKey,PersistentDataType.STRING);if(action==null)return;e.setCancelled(true);Player p=e.getPlayer();if(action.startsWith("skill:")){if(!players.contains(p.getUniqueId()))return;usePlayerSkill(p,PlayerSkill.valueOf(action.substring(6)));}else if(p.getUniqueId().equals(oni))useOniSkill(p,action);else if(action.equals("tracker"))trackHeart(p);}
    private boolean ready(Player p,String id,int cd){String k=p.getUniqueId()+":"+id;long now=System.currentTimeMillis();long until=cooldowns.getOrDefault(k,0L);if(until>now){msg(p,"あと &e"+((until-now+999)/1000)+"秒 &f待ってください。");return false;}cooldowns.put(k,now+cd*1000L);return true;}
    private void usePlayerSkill(Player p,PlayerSkill s){if(!ready(p,s.name(),s.cooldown))return;switch(s){case SPRINT->p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,100,2,false,true));case INVISIBLE->p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,120,0,false,true));case SMOKE->{p.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,p.getLocation(),160,4,2,4,.03);Player o=Bukkit.getPlayer(oni);if(o!=null&&o.getLocation().distanceSquared(p.getLocation())<64)o.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,80,0,false,true));}case ONI_STRIKE->{Player o=Bukkit.getPlayer(oni);if(o!=null&&o.getWorld().equals(p.getWorld())&&o.getLocation().distanceSquared(p.getLocation())<=25){double damage=brokenHearts==totalHearts?12:3;o.damage(damage,p);o.setVelocity(o.getLocation().toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.5).setY(.5));}else msg(p,"鬼が5ブロック以内にいません。");}}}
    private void useOniSkill(Player p,String action){int heartPenalty=brokenHearts*3;switch(action){case "dakko_tp"->{if(!ready(p,action,20+heartPenalty))return;Block b=p.getTargetBlockExact(Math.max(6,20-brokenHearts*3));if(b!=null)p.teleport(b.getLocation().add(.5,1,.5).setDirection(p.getLocation().getDirection()));}case "dakko_clone"->{if(brokenHearts>=3||!ready(p,action,35+heartPenalty))return;for(int n=0;n<Math.max(1,3-brokenHearts/2);n++){Fox f=p.getWorld().spawn(p.getLocation(),Fox.class);f.setCustomName(cc("&c"+p.getName()));f.setCustomNameVisible(true);f.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,200,1));Bukkit.getScheduler().runTaskLater(this,f::remove,200);}}case "kishin_charge"->{if(!ready(p,action,18+heartPenalty))return;p.setVelocity(p.getLocation().getDirection().normalize().multiply(Math.max(1.4,2.6-brokenHearts*.2)).setY(.15));p.getWorld().playSound(p.getLocation(),Sound.ENTITY_RAVAGER_ROAR,1,1);}case "kishin_slam"->{if(brokenHearts>=3||!ready(p,action,30+heartPenalty))return;p.getWorld().spawnParticle(Particle.EXPLOSION_LARGE,p.getLocation(),4);for(UUID id:players){Player q=Bukkit.getPlayer(id);if(q!=null&&!dead.contains(id)&&q.getWorld().equals(p.getWorld())&&q.getLocation().distanceSquared(p.getLocation())<49){q.damage(6,p);q.setVelocity(q.getLocation().toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.2).setY(.8));q.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,60,1));}}}}}
    private void trackHeart(Player p){Location nearest=heartHp.keySet().stream().map(LocationStore::decode).filter(Objects::nonNull).min(Comparator.comparingDouble(l->l.distanceSquared(p.getLocation()))).orElse(null);if(nearest==null){msg(p,"残っている心臓はありません。");return;}p.setCompassTarget(nearest);msg(p,"最寄りの心臓まで約 &e"+(int)nearest.distance(p.getLocation())+"m");}

    @EventHandler public void onDamage(EntityDamageByEntityEvent e){if(state!=GameState.RUNNING||!(e.getEntity() instanceof Player victim))return;Player attacker=e.getDamager() instanceof Player p?p:(e.getDamager() instanceof Projectile pr&&pr.getShooter() instanceof Player p?p:null);if(attacker==null)return;boolean av=attacker.getUniqueId().equals(oni), vv=victim.getUniqueId().equals(oni);if(av==vv&&!getConfig().getBoolean("friendly-fire",false)){e.setCancelled(true);return;}if(vv&&brokenHearts<totalHearts)e.setDamage(e.getDamage()*.1);}
    @EventHandler public void onDeath(PlayerDeathEvent e){if(state!=GameState.RUNNING||!participants.contains(e.getPlayer().getUniqueId()))return;e.getDrops().clear();e.setDeathMessage(null);Bukkit.getScheduler().runTask(this,()->{Player p=e.getPlayer();p.spigot().respawn();p.setGameMode(GameMode.SPECTATOR);if(p.getUniqueId().equals(oni))end(false,"&c鬼 討 滅――ぷれいやーの勝利");else{dead.add(p.getUniqueId());all("&7"+p.getName()+" は鬼に喰われた……");checkPlayerOutcome();}});}
    @EventHandler public void onQuit(PlayerQuitEvent e){if(state!=GameState.RUNNING)return;UUID id=e.getPlayer().getUniqueId();if(id.equals(oni))end(false,"鬼が退出したため、ぷれいやーの勝利");else if(players.contains(id)){dead.add(id);checkPlayerOutcome();}}

    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){if(a.length==1)return List.of("join","leave","skill","gmbook","heartmode","marker","markers","set","heart","start","stop","status");if(a.length==2&&a[0].equalsIgnoreCase("skill"))return List.of("sprint","invisible","smoke","strike");if(a.length==2&&a[0].equalsIgnoreCase("heartmode"))return List.of("random","manual");if(a.length==2&&a[0].equalsIgnoreCase("set"))return List.of("lobby","player","oni","exit");if(a.length==2&&a[0].equalsIgnoreCase("start"))return List.of("dakko","kishin");return List.of();}
}
