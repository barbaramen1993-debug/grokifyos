extends CharacterBody3D
## Universal controllable actor — same stick/jump language as Companion Web stage.
## Swap the Visual child for a VRM instance (godot-vrm) without changing this script.

signal possessed_changed(actor_id: String)
signal landed
signal jumped

@export var actor_id: String = "player"
@export var move_speed: float = 4.2
@export var acceleration: float = 18.0
@export var friction: float = 14.0
@export var jump_velocity: float = 4.8
@export var gravity: float = 16.5
@export var turn_speed: float = 10.0
@export var air_control: float = 0.45

## Camera used for camera-relative stick (SpringArm3D target or Camera3D).
@export var camera_path: NodePath = ^"../CameraRig/SpringArm3D/Camera3D"

var _input_move := Vector2.ZERO
var _jump_edge := false
var _jump_held := false
var _possessed := true
var _yaw := 0.0

@onready var _visual: Node3D = $Visual
@onready var _camera: Camera3D = get_node_or_null(camera_path) as Camera3D


func _ready() -> void:
	_yaw = rotation.y
	add_to_group("controllable_actors")
	add_to_group("actor_%s" % actor_id)


func set_control_input(move_x: float, move_y: float, jump: bool, jump_edge: bool = false) -> void:
	## Mirrors CompanionStage.setControlInput: moveX/moveY in stick space, Y = forward.
	_input_move = Vector2(move_x, move_y)
	if jump_edge:
		_jump_edge = true
	_jump_held = jump


func possess(on: bool = true) -> void:
	_possessed = on
	possessed_changed.emit(actor_id if on else "")


func is_possessed() -> bool:
	return _possessed


func _physics_process(delta: float) -> void:
	if not _possessed:
		_apply_gravity(delta)
		move_and_slide()
		return

	var wish := _wish_direction()
	var target_h := wish * move_speed
	var hvel := Vector3(velocity.x, 0.0, velocity.z)
	var accel := acceleration if is_on_floor() else acceleration * air_control
	if wish.length_squared() > 0.0001:
		hvel = hvel.move_toward(target_h, accel * delta)
		# Face move direction.
		var face := atan2(-wish.x, -wish.z)
		_yaw = lerp_angle(_yaw, face, clamp(turn_speed * delta, 0.0, 1.0))
		rotation.y = _yaw
		if _visual:
			_visual.rotation.y = 0.0
	else:
		hvel = hvel.move_toward(Vector3.ZERO, friction * delta)

	velocity.x = hvel.x
	velocity.z = hvel.z

	_apply_gravity(delta)

	if _jump_edge and is_on_floor():
		velocity.y = jump_velocity
		_jump_edge = false
		jumped.emit()
	elif not _jump_held:
		_jump_edge = false

	var was_air := not is_on_floor()
	move_and_slide()
	if was_air and is_on_floor():
		landed.emit()


func _apply_gravity(delta: float) -> void:
	if not is_on_floor():
		velocity.y -= gravity * delta
	elif velocity.y < 0.0:
		velocity.y = -0.5


func _wish_direction() -> Vector3:
	var stick := _input_move
	# Keyboard fallback when no touch stick.
	var kb := Input.get_vector("move_left", "move_right", "move_forward", "move_back")
	if stick.length_squared() < 0.0001:
		stick = kb
	if Input.is_action_just_pressed("jump"):
		_jump_edge = true
		_jump_held = true
	elif not Input.is_action_pressed("jump"):
		_jump_held = false

	if stick.length_squared() < 0.0001:
		return Vector3.ZERO

	var basis_yaw := _camera_yaw()
	var forward := Vector3(-sin(basis_yaw), 0.0, -cos(basis_yaw))
	var right := Vector3(cos(basis_yaw), 0.0, -sin(basis_yaw))
	var dir := (right * stick.x + forward * stick.y)
	if dir.length_squared() > 1.0:
		dir = dir.normalized()
	return dir


func _camera_yaw() -> float:
	if _camera and is_instance_valid(_camera):
		return _camera.global_rotation.y
	return rotation.y
