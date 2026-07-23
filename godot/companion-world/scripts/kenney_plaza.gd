extends Node3D
## Playable plaza built from vendored Kenney CC0 platformer GLBs.
## Source: https://github.com/KenneyNL/Starter-Kit-3D-Platformer

const MODEL_DIR := "res://maps/vendor/kenney-platformer/models/"

@onready var _props: Node3D = $Props


func _ready() -> void:
	_build()


func _build() -> void:
	# Base pads
	_place("platform-large.glb", Vector3(0, 0, 0), 1.0)
	_place("platform-grass-large-round.glb", Vector3(0, 0.02, 0), 1.1)
	# Stepping platforms
	_place("platform-medium.glb", Vector3(4.5, 0.6, -2.5), 1.0)
	_place("platform-medium.glb", Vector3(-4.2, 0.9, -1.2), 1.0)
	_place("platform.glb", Vector3(2.2, 1.4, -5.5), 1.0)
	_place("platform.glb", Vector3(-1.5, 1.8, -6.2), 1.0)
	_place("platform-large.glb", Vector3(0, 2.2, -9.0), 0.7)
	# Decor
	_place("grass.glb", Vector3(3.2, 0.05, 2.4), 1.2)
	_place("grass-small.glb", Vector3(-2.8, 0.05, 1.6), 1.0)
	_place("grass.glb", Vector3(-5.5, 0.05, -3.0), 1.0)
	_place("flag.glb", Vector3(0, 2.35, -9.0), 1.0)
	_place("cloud.glb", Vector3(6, 5.5, -4), 1.4)
	_place("cloud.glb", Vector3(-7, 6.2, -8), 1.2)
	_place("block-coin.glb", Vector3(2.2, 2.1, -5.5), 1.0)
	_place("coin.glb", Vector3(-1.5, 2.5, -6.2), 1.0)
	# Soft perimeter walls so players don't fall forever
	_add_wall(Vector3(0, 0.6, -18), Vector3(36, 1.4, 0.4))
	_add_wall(Vector3(0, 0.6, 18), Vector3(36, 1.4, 0.4))
	_add_wall(Vector3(-18, 0.6, 0), Vector3(0.4, 1.4, 36))
	_add_wall(Vector3(18, 0.6, 0), Vector3(0.4, 1.4, 36))
	# Ground visual
	var floor_mesh := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = Vector3(48, 0.4, 48)
	floor_mesh.mesh = box
	floor_mesh.position = Vector3(0, -0.2, 0)
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.12, 0.28, 0.16)
	mat.roughness = 0.95
	floor_mesh.material_override = mat
	add_child(floor_mesh)


func _place(file: String, pos: Vector3, scale: float) -> void:
	var path := MODEL_DIR + file
	if not ResourceLoader.exists(path):
		push_warning("KenneyPlaza: missing %s" % path)
		return
	var packed := load(path)
	if packed == null:
		return
	var node: Node = null
	if packed is PackedScene:
		node = (packed as PackedScene).instantiate()
	elif packed is Mesh:
		var mi := MeshInstance3D.new()
		mi.mesh = packed as Mesh
		node = mi
	else:
		# GLB often imports as PackedScene; if not, try as Node.
		node = packed.duplicate() if packed is Node else null
	if node == null:
		return
	if node is Node3D:
		(node as Node3D).position = pos
		(node as Node3D).scale = Vector3.ONE * scale
	_props.add_child(node)
	# Approximate collision for platforms (box under mesh).
	if file.begins_with("platform"):
		_add_platform_body(pos, scale)


func _add_platform_body(pos: Vector3, scale: float) -> void:
	var body := StaticBody3D.new()
	body.position = pos + Vector3(0, 0.15 * scale, 0)
	var col := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = Vector3(2.4 * scale, 0.35 * scale, 2.4 * scale)
	col.shape = shape
	body.add_child(col)
	body.collision_layer = 1
	add_child(body)


func _add_wall(pos: Vector3, size: Vector3) -> void:
	var body := StaticBody3D.new()
	body.position = pos
	var col := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = size
	col.shape = shape
	body.add_child(col)
	var mesh := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = size
	mesh.mesh = box
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.2, 0.35, 0.22, 0.85)
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mesh.material_override = mat
	body.add_child(mesh)
	add_child(body)
