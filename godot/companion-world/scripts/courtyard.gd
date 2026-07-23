extends Node3D
## Open courtyard — primitive stand-in for KayKit / Quaternius village packs.
## Swap meshes later via maps/vendor after running fetch_map_pack.sh.


func _ready() -> void:
	_build()


func _build() -> void:
	_box(Vector3(0, -0.2, 0), Vector3(28, 0.4, 28), Color(0.22, 0.2, 0.18), true)
	# Fountain base
	_box(Vector3(0, 0.35, 0), Vector3(3.2, 0.7, 3.2), Color(0.45, 0.48, 0.55), true)
	_box(Vector3(0, 0.9, 0), Vector3(1.2, 0.4, 1.2), Color(0.5, 0.55, 0.65), true)
	_cyl(Vector3(0, 1.4, 0), 0.35, 1.0, Color(0.55, 0.7, 0.85))
	# Colonnade / walls
	for x in [-10.0, 10.0]:
		for z in range(-8, 9, 4):
			_box(Vector3(x, 1.5, float(z)), Vector3(0.7, 3.0, 0.7), Color(0.55, 0.5, 0.42), true)
			_box(Vector3(x, 3.2, float(z)), Vector3(1.4, 0.35, 1.4), Color(0.48, 0.42, 0.36), true)
	for z in [-10.0, 10.0]:
		for x in range(-8, 9, 4):
			_box(Vector3(float(x), 1.5, z), Vector3(0.7, 3.0, 0.7), Color(0.55, 0.5, 0.42), true)
	# Benches
	_box(Vector3(4, 0.35, 5), Vector3(2.2, 0.35, 0.55), Color(0.35, 0.22, 0.12), true)
	_box(Vector3(-4, 0.35, 5), Vector3(2.2, 0.35, 0.55), Color(0.35, 0.22, 0.12), true)
	# Perimeter
	_box(Vector3(0, 0.8, -14), Vector3(28, 1.6, 0.5), Color(0.4, 0.36, 0.3), true)
	_box(Vector3(0, 0.8, 14), Vector3(28, 1.6, 0.5), Color(0.4, 0.36, 0.3), true)
	_box(Vector3(-14, 0.8, 0), Vector3(0.5, 1.6, 28), Color(0.4, 0.36, 0.3), true)
	_box(Vector3(14, 0.8, 0), Vector3(0.5, 1.6, 28), Color(0.4, 0.36, 0.3), true)
	# Planters
	for p in [Vector3(6, 0.4, -4), Vector3(-6, 0.4, -4), Vector3(6, 0.4, 2), Vector3(-6, 0.4, 2)]:
		_box(p, Vector3(1.4, 0.8, 1.4), Color(0.4, 0.28, 0.18), true)
		_box(p + Vector3(0, 0.55, 0), Vector3(1.1, 0.3, 1.1), Color(0.2, 0.45, 0.22), false)


func _box(pos: Vector3, size: Vector3, color: Color, collide: bool) -> void:
	var mi := MeshInstance3D.new()
	var mesh := BoxMesh.new()
	mesh.size = size
	mi.mesh = mesh
	mi.position = pos
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	mat.roughness = 0.88
	mi.material_override = mat
	add_child(mi)
	if collide:
		var body := StaticBody3D.new()
		body.position = pos
		var col := CollisionShape3D.new()
		var shape := BoxShape3D.new()
		shape.size = size
		col.shape = shape
		body.add_child(col)
		add_child(body)


func _cyl(pos: Vector3, radius: float, height: float, color: Color) -> void:
	var mi := MeshInstance3D.new()
	var mesh := CylinderMesh.new()
	mesh.top_radius = radius
	mesh.bottom_radius = radius
	mesh.height = height
	mi.mesh = mesh
	mi.position = pos
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	mat.roughness = 0.4
	mat.metallic = 0.15
	mi.material_override = mat
	add_child(mi)
