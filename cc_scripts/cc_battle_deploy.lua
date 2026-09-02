-- Steve's Army - ComputerCraft example: Western assault on objective
-- Objective 4829 18 6178
-- Three enemy squads spawned from the west (x=4730), spread +-60 on Z
-- Each soldier gets USSR.rifleman loadout (AK47 + full kit) on spawn

local OBJ_X, OBJ_Y, OBJ_Z = 4829, 18, 6178
local SPAWN_X, SPAWN_Y     = 4730, 18
local BASE_Z               = 6178
local Z_SPREAD             = 60.0
local SQUADS               = {"en-north", "en-center", "en-south"}
local SOLDIERS_PER_SQUAD   = 6

-- USSR.rifleman loadout (from riverbend_coldwar.json)
local LOADOUT = '{Items:['..
  '{Slot:0,Count:1b,tag:{Damage:0},id:"combatgear:ssh_helmet"},'..
  '{Slot:1,Count:1b,tag:{Damage:0},id:"combatgear:wwi_chestplate"},'..
  '{Slot:2,Count:1b,tag:{Damage:0},id:"combatgear:heavycloak_leggings"},'..
  '{Slot:3,Count:1b,tag:{Damage:0},id:"combatgear:emr_boots"},'..
  '{Slot:4,Count:8b,id:"combatgear:bandages"},'..
  '{Slot:5,Count:1b,tag:{HasBulletInBarrel:1b,GunCurrentAmmoCount:30,GunFireMode:"AUTO",GunId:"tacz:ak47"},id:"tacz:modern_kinetic_gun"},'..
  '{Slot:6,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:7,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:8,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:9,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:10,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:11,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:12,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:13,Count:60b,tag:{AmmoId:"tacz:762x39"},id:"tacz:ammo"},'..
  '{Slot:14,Count:5b,id:"cgm:grenade"},'..
  '{Slot:15,Count:1b,tag:{Damage:0},id:"minecraft:flint_and_steel"},'..
  '{Slot:16,Count:2b,id:"smallarm:smoke_grenade"},'..
  '{Slot:17,Count:5b,id:"combatgear:rations"},'..
  '{Slot:18,Count:3b,id:"minecraft:tnt"},'..
  '{Slot:19,Count:9b,id:"minecraft:oak_planks"},'..
  '{Slot:20,Count:1b,id:"minecraft:chest"},'..
  '{Slot:21,Count:1b,tag:{Damage:0},id:"minecraft:iron_shovel"}'..
']}'

local function run(cmd)
  local ok = commands.exec(cmd)
  if not ok then print("[FAIL] " .. cmd) end
  return ok
end

print("[1/3] Creating callsign squads...")
for _, cs in ipairs(SQUADS) do
  print("  Creating squad: " .. cs)
  run("stevesarmy squad create " .. cs .. " enemy")
end

print("[2/3] Spawning equipped soldiers along the western front...")
for i, cs in ipairs(SQUADS) do
  local zOff   = (i - 2) * Z_SPREAD
  local spawnZ = BASE_Z + zOff
  print("  Squad " .. cs .. " frontline Z=" .. math.floor(spawnZ))
  for j = 1, SOLDIERS_PER_SQUAD do
    local z = spawnZ + (j - math.ceil(SOLDIERS_PER_SQUAD / 2)) * 3
    print("    Spawn " .. cs .. " #" .. j .. " at " .. SPAWN_X .. " " .. SPAWN_Y .. " " .. math.floor(z))
    run(string.format(
      "stevesarmy spawn enemy %d %d %d 0 0 %s",
      SPAWN_X, SPAWN_Y, math.floor(z), LOADOUT))
  end
end

print("[3/3] Ordering ATTACK on objective...")
sleep(1)
for _, cs in ipairs(SQUADS) do
  print("  " .. cs .. " -> ATTACK " .. OBJ_X .. " " .. OBJ_Y .. " " .. OBJ_Z)
  run(string.format(
    "stevesarmy squad order %s attack %d %d %d",
    cs, OBJ_X, OBJ_Y, OBJ_Z))
end

print("Done - " .. #SQUADS * SOLDIERS_PER_SQUAD .. " equipped soldiers ATTACKing " .. OBJ_X .. " " .. OBJ_Y .. " " .. OBJ_Z)
