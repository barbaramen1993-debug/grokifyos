extends Node3D
## Third-person spring arm: follows possessed actor, orbit via right-side drag.

@export var target_path: NodePath = ^"../Player"
@export var follow_height: float = 1.35
@export var follow_distance: float = 3.4
@export var follow_lerp: float = 8.0
@export var orbit_sensitivity: float = 0.0045
@export var pitch_min: float = -0.55
@export var pitch_max: float = 0.85

var _yaw := 0.4
var _pitch := 0.28
var _dragging := false
var _last_pos := Vector2.ZERO

@onready var _spring: SpringArm3D = $SpringArm3D
@onready var _target: Node3D = get_node_or_null(target_path) as Node3D


func _ready() -> void:
	if _spring:
		_spring.spring_length = follow_distance
		_spring.collision_mask = 1


func _process(delta: float) -> void:
	if _target == null or not is_instance_valid(_target):
		return
	var goal := _target.global_position + Vector3(0.0, follow_height, 0.0)
	global_position = global_position.lerp(goal, clamp(follow_lerp * delta, 0.0, 1.0))
	rotation = Vector3(_pitch, _yaw, 0.0)


func _unhandled_input(event: InputEvent) -> void:
	# Right half of screen / mouse drag orbits (left half is stick HUD).
	if event is InputEventScreenTouch:
		var st := event as InputEventScreenTouch
		if st.pressed and _is_orbit_side(st.position):
			_dragging = true
			_last_pos = st.position
		elif not st.pressed:
			_dragging = false
	elif event is InputEventScreenDrag and _dragging:
		var sd := event as InputEventScreenDrag
		_orbit(sd.relative)
	elif event is InputEventMouseButton:
		var mb := event as InputEventMouseButton
		if mb.button_index == MOUSE_BUTTON_RIGHT or mb.button_index == MOUSE_BUTTON_LEFT:
			if mb.pressed and _is_orbit_side(mb.position):
				_dragging = true
				_last_pos = mb.position
			else:
				_dragging = false
	elif event is InputEventMouseMotion and _dragging:
		var mm := event as InputEventMouseMotion
		_orbit(mm.relative)


func _orbit(rel: Vector2) -> void:
	_yaw -= rel.x * orbit_sensitivity
	_pitch = clamp(_pitch - rel.y * orbit_sensitivity, pitch_min, pitch_max)


func _is_orbit_side(pos: Vector2) -> bool:
	var vp := get_viewport().get_visible_rect().size
	# Leave left 42% for virtual stick.
	return pos.x > vp.x * 0.42
