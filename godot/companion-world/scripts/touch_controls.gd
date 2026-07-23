extends Control
## On-screen stick + jump — same contract as companion-control.js → setControlInput.

@export var player_path: NodePath = ^"../../Player"
@export var dead_zone: float = 0.12
@export var max_radius: float = 72.0

var _stick_id: int = -1
var _stick := Vector2.ZERO
var _jump_held := false

@onready var _player: Node = get_node_or_null(player_path)
@onready var _stick_zone: Control = %StickZone
@onready var _stick_base: Control = %StickBase
@onready var _stick_knob: Control = %StickKnob
@onready var _jump_btn: Button = %JumpBtn
@onready var _map_btn: Button = get_node_or_null("%MapBtn")
@onready var _map_label: Label = get_node_or_null("%MapLabel")


func _ready() -> void:
	if _jump_btn:
		_jump_btn.button_down.connect(_on_jump_down)
		_jump_btn.button_up.connect(_on_jump_up)
	if _stick_zone:
		_stick_zone.gui_input.connect(_on_stick_gui)
	if _map_btn:
		_map_btn.pressed.connect(_on_map_pressed)
	_refresh_map_label()
	if Engine.has_singleton("WorldBridge") == false and has_node("/root/WorldBridge"):
		var wb := get_node_or_null("/root/WorldBridge")
		if wb and wb.has_signal("map_loaded"):
			wb.map_loaded.connect(func(_id): _refresh_map_label())
	elif has_node("/root/WorldBridge"):
		var wb2 := get_node("/root/WorldBridge")
		if wb2.has_signal("map_loaded"):
			wb2.map_loaded.connect(func(_id): _refresh_map_label())
	set_process(true)


func _on_map_pressed() -> void:
	var wb := get_node_or_null("/root/WorldBridge")
	if wb and wb.has_method("next_map"):
		wb.call("next_map")
	_refresh_map_label()


func _refresh_map_label() -> void:
	if _map_label == null:
		return
	var wb := get_node_or_null("/root/WorldBridge")
	if wb and wb.has_method("get_current_map"):
		_map_label.text = str(wb.call("get_current_map"))
	else:
		_map_label.text = "map"


func _process(_delta: float) -> void:
	_push()


func _gui_input(event: InputEvent) -> void:
	# Stick zone handles its own events via StickZone.
	pass


func _on_stick_gui(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		var st := event as InputEventScreenTouch
		if st.pressed and _stick_id < 0:
			_stick_id = st.index
			_update_stick(st.position)
			accept_event()
		elif not st.pressed and st.index == _stick_id:
			_reset_stick()
			accept_event()
	elif event is InputEventScreenDrag and (event as InputEventScreenDrag).index == _stick_id:
		_update_stick((event as InputEventScreenDrag).position)
		accept_event()
	elif event is InputEventMouseButton:
		var mb := event as InputEventMouseButton
		if mb.button_index == MOUSE_BUTTON_LEFT:
			if mb.pressed:
				_stick_id = 0
				_update_stick(mb.position)
			else:
				_reset_stick()
			accept_event()
	elif event is InputEventMouseMotion and _stick_id >= 0 and Input.is_mouse_button_pressed(MOUSE_BUTTON_LEFT):
		_update_stick((event as InputEventMouseMotion).position)
		accept_event()


func _update_stick(local_pos: Vector2) -> void:
	if _stick_base == null:
		return
	var center := _stick_base.size * 0.5
	var delta := local_pos - center
	# If event is in StickZone coords, map relative to base center in zone space.
	if _stick_base.get_parent() is Control:
		var zone := _stick_base.get_parent() as Control
		var base_center := _stick_base.position + _stick_base.size * 0.5
		delta = local_pos - base_center
		var _u := zone # silence
	var len := delta.length()
	var r := minf(max_radius, minf(_stick_base.size.x, _stick_base.size.y) * 0.42)
	if len > r and len > 0.0:
		delta = delta / len * r
		len = r
	if _stick_knob:
		_stick_knob.position = (_stick_base.size * 0.5) + delta - _stick_knob.size * 0.5
	var nx := delta.x / r if r > 0.0 else 0.0
	var ny := delta.y / r if r > 0.0 else 0.0
	# Screen up → forward (+moveY), matching Web companion-control.js
	var mx := nx
	var my := -ny
	var mag := Vector2(mx, my).length()
	if mag < dead_zone:
		_stick = Vector2.ZERO
	else:
		var adj := (mag - dead_zone) / (1.0 - dead_zone)
		_stick = Vector2(mx, my).normalized() * minf(1.0, adj)
	_push()


func _reset_stick() -> void:
	_stick_id = -1
	_stick = Vector2.ZERO
	if _stick_knob and _stick_base:
		_stick_knob.position = (_stick_base.size - _stick_knob.size) * 0.5
	_push()


func _on_jump_down() -> void:
	_jump_held = true
	_push(true)


func _on_jump_up() -> void:
	_jump_held = false
	_push()


func _push(jump_edge: bool = false) -> void:
	if _player and _player.has_method("set_control_input"):
		_player.call("set_control_input", _stick.x, _stick.y, _jump_held, jump_edge)
