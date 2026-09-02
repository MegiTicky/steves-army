-- Steve's Army - ComputerCraft example: Western assault on objective
-- Objective 4829 18 6178
-- Three enemy squads spawned from the west (x=4730), spread +-60 on Z
-- Each soldier gets an AK47 via give command before ATTACK order

local OBJ_X, OBJ_Y, OBJ_Z = 4829, 18, 6178
local SPAWN_X, SPAWN_Y     = 4730, 18
local BASE_Z               = 6178
local Z_SPREAD             = 60.0
local SQUADS               = {"en-north", "en-center", "en-south"}
local SOLDIERS_PER_SQUAD   = 6
local AK47_ID              = "tacz:ak47"

local function run(cmd)
  local ok, out = commands.exec(cmd)
  if not ok then print("[FAIL] " .. cmd .. "\n" .. (out or "")) end
  return ok, out
end

print("[1/4] Creating callsign squads...")
for _, cs in ipairs(SQUADS) do
  print("  Creating squad: " .. cs)
  run("stevesarmy squad create " .. cs .. " enemy")
end

print("[2/4] Spawning soldiers along the western front...")
for i, cs in ipairs(SQUADS) do
  local zOff   = (i - 2) * Z_SPREAD
  local spawnZ = BASE_Z + zOff
  print("  Squad " .. cs .. " frontline Z=" .. math.floor(spawnZ))
  for j = 1, SOLDIERS_PER_SQUAD do
    local z = spawnZ + (j - math.ceil(SOLDIERS_PER_SQUAD / 2)) * 3
    print("    Spawn " .. cs .. " #" .. j .. " at " .. SPAWN_X .. " " .. SPAWN_Y .. " " .. math.floor(z))
    run(string.format(
      "stevesarmy spawn enemy squad %s %d %d %d",
      cs, SPAWN_X, SPAWN_Y, math.floor(z)))
  end
end

print("[3/4] Equipping AK47s...")
sleep(1)
for _, cs in ipairs(SQUADS) do
  -- Query squad info to get member UUIDs
  local ok, out = run("stevesarmy squad info " .. cs)
  if ok and out then
    for uuid in out:gmatch("(%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x)") do
      print("  Giving AK47 to " .. uuid)
      run("give " .. uuid .. " " .. AK47_ID)
    end
  end
end

print("[4/4] Ordering ATTACK on objective...")
sleep(1)
for _, cs in ipairs(SQUADS) do
  print("  " .. cs .. " -> ATTACK " .. OBJ_X .. " " .. OBJ_Y .. " " .. OBJ_Z)
  run(string.format(
    "stevesarmy squad order %s attack %d %d %d",
    cs, OBJ_X, OBJ_Y, OBJ_Z))
end

print("Done - 3 squads with AK47s ATTACKing " .. OBJ_X .. " " .. OBJ_Y .. " " .. OBJ_Z)
