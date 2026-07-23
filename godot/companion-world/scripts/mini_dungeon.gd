extends Node3D
## Compact multi-room dungeon — layout stand-in for KayKit Dungeon (CC0).
## Fetch real meshes: ./scripts/fetch_map_pack.sh kaykit-dungeon


func _ready() -> void:
	_build()


func _build() -> void:
	var stone := Color(0.28, 0.3, 0.36)
	var floor_c := Color(0.16, 0.17, 0.22)
	var accent := Color(0.55, 0.35, 0.2)
	# Main hall floor
	_box(Vector3(0, -0.15, 0), Vector3(14, 0.3, 18), floor_c, true)
	# Walls main hall
	_box(Vector3(0, 1.4, -9), Vector3(14, 2.8, 0.5), stone, true)
	_box(Vector3(-7, 1.4, 0), Vector3(0.5, 2.8, 18), stone, true)
	_box(Vector3(7, 1.4, 0), Vector3(0.5, 2.8, 18), stone, true)
	# Back wall with doorway gap → north room
	_box(Vector3(-4.2, 1.4, 9), Vector3(5.6, 2.8, 0.5), stone, true)
	_box(Vector3(4.2, 1.4, 9), Vector3(5.6, 2.8, 0.5), stone, true)
	# North room
	_box(Vector3(0, -0.15, 14), Vector3(10, 0.3, 8), floor_c, true)
	_box(Vector3(0, 1.4, 18), Vector3(10, 2.8, 0.5), stone, true)
	_box(Vector3(-5, 1.4, 14), Vector3(0.5, 2.8, 8), stone, true)
	_box(Vector3(5, 1.4, 14), Vector3(0.5, 2.8, 8), stone, true)
	# East alcove
	_box(Vector3(10, -0.15, 0), Vector3(6, 0.3, 6), floor_c, true)
	_box(Vector3(10, 1.4, -3), Vector3(6, 2.8, 0.5), stone, true)
	_box(Vector3(10, 1.4, 3), Vector3(6, 2.8, 0.5), stone, true)
	_box(Vector3(13, 1.4, 0), Vector3(0.5, 2.8, 6), stone, true)
	# Opening from hall to east: carve by not placing wall at (7,0)
	# Pillars
	for z in [-4.0, 0.0, 4.0]:
		_box(Vector3(-3.5, 1.2, z), Vector3(0.7, 2.4, 0.7), stone, true)
		_box(Vector3(3.5, 1.2, z), Vector3(0.7, 2.4, 0.7), stone, true)
	# Props
	_box(Vector3(-4.5, 0.45, -6), Vector3(1.6, 0.9, 0.9), accent, true)
	_box(Vector3(4.5, 0.55, 5), Vector3(1.2, 1.1, 1.2), accent, true)
	_box(Vector3(0, 0.4, 15), Vector3(2.4, 0.8, 1.0), Color(0.4, 0.25, 0.15), true)
	# Ceiling dimmer plates (visual only)
	_box(Vector3(0, 3.0, 0), Vector3(13.5, 0.15, 17.5), Color(0.12, 0.13, 0.16), false)
	_box(Vector3(0, 3.0, 14), Vector3(9.5, 0.15, 7.5), Color(0.12, 0.13, 0.16), false)
	# Torch-ish lights
	_omni(Vector3(-3.5, 2.2, -4), Color(1.0, 0.65, 0.3), 1.2)
	_omni(Vector3(3.5, 2.2, 4), Color(1.0, 0.65, 0.3), 1.2)
	_omni(Vector3(0, 2.0, 14), Color(0.6, 0.75, 1.0), 0.9)


func _box(pos: Vector3, size: Vector3, color: Color, collide: bool) -> void:
	var mi := MeshInstance3D.new()
	var mesh := BoxMesh.new()
	mesh.size = size
	mi.mesh = mesh
	mi.position = pos
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	mat.roughness = 0.92
	mi.material_override = mat
	add_child(mi)
	if collide and size.y > 0.05:
		var body := StaticBody3D.new()
		body.position = pos
		var col := CollisionShape3D.new()
		var shape := BoxShape3D.new()
		shape.size = size
		col.shape = shape
		body.add_child(col)
		add_child(body)


func _omni(pos: Vector3, color: Color, energy: float) -> void:
	var light := OmniLight3D.new()
	light.position = pos
	light.light_color = color
	light.light_energy = energy
	light.omni_range = 8.0
	add_child(light)
