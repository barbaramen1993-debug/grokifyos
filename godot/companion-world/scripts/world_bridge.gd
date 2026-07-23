extends Node
## Host bridge for Android / Companion app.
## Mirrors the Web stage actor API: register / possess / control / load map.
##
## Android can call via Godot's Java singleton later:
##   WorldBridge.set_control_input(x, y, jump, jump_edge)
##   WorldBridge.possess("player")
##   WorldBridge.load_map("kenney_plaza")

signal map_loaded(map_id: String)
signal actor_possessed(actor_id: String)

var _possessed_id: String = "player"
var _current_map: String = "proto_arena"

const MAP_IDS: PackedStringArray = [
	"proto_arena",
	"kenney_plaza",
	"courtyard",
	"mini_dungeon",
]


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS


func set_control_input(move_x: float, move_y: float, jump: bool = false, jump_edge: bool = false) -> void:
	var actor := _possessed_actor()
	if actor and actor.has_method("set_control_input"):
		actor.call("set_control_input", move_x, move_y, jump, jump_edge)


func possess(actor_id: String) -> bool:
	var found := false
	for n in get_tree().get_nodes_in_group("controllable_actors"):
		if n.has_method("possess"):
			var on := str(n.get("actor_id")) == actor_id
			n.call("possess", on)
			if on:
				found = true
				_possessed_id = actor_id
	if found:
		actor_possessed.emit(actor_id)
	return found


func get_possessed_id() -> String:
	return _possessed_id


func load_map(map_id: String) -> bool:
	## Swap map root. Maps live under res://maps/<id>.tscn
	var id := map_id.strip_edges()
	if id.is_empty():
		return false
	var path := "res://maps/%s.tscn" % id
	if not ResourceLoader.exists(path):
		push_warning("WorldBridge: map missing %s" % path)
		return false
	var tree := get_tree()
	var main := tree.current_scene
	if main == null:
		return false
	var holder := main.get_node_or_null("MapRoot")
	if holder == null:
		push_warning("WorldBridge: MapRoot missing on main scene")
		return false
	# Immediate remove so we can re-instance without waiting a frame.
	for c in holder.get_children():
		holder.remove_child(c)
		c.free()
	var packed := load(path) as PackedScene
	if packed == null:
		return false
	var inst := packed.instantiate()
	holder.add_child(inst)
	_current_map = id
	_warp_player_to_spawn(inst)
	map_loaded.emit(id)
	return true


func get_current_map() -> String:
	return _current_map


func list_maps() -> PackedStringArray:
	return MAP_IDS


func next_map() -> String:
	var maps := list_maps()
	if maps.is_empty():
		return _current_map
	var idx := 0
	for i in maps.size():
		if maps[i] == _current_map:
			idx = (i + 1) % maps.size()
			break
	var nid: String = maps[idx]
	load_map(nid)
	return nid


func _warp_player_to_spawn(map_root: Node) -> void:
	var spawn := map_root.find_child("Spawn", true, false)
	var player := _possessed_actor()
	if player == null:
		var main := get_tree().current_scene
		if main:
			player = main.get_node_or_null("Player")
	if player is Node3D and spawn is Node3D:
		(player as Node3D).global_position = (spawn as Node3D).global_position
		if player is CharacterBody3D:
			(player as CharacterBody3D).velocity = Vector3.ZERO


func _possessed_actor() -> Node:
	for n in get_tree().get_nodes_in_group("controllable_actors"):
		if n.has_method("is_possessed") and n.call("is_possessed"):
			return n
		if str(n.get("actor_id")) == _possessed_id:
			return n
	return null
