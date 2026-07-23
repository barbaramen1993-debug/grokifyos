/**
 * Companion World maps — in-app play space mirroring godot/companion-world.
 * Same map ids / layouts as WorldBridge (proto_arena, kenney_plaza, courtyard, mini_dungeon).
 * No separate Godot package required; maps ship inside the GrokifyOS APK.
 */
(function (global) {
  "use strict";

  var MAP_IDS = ["proto_arena", "kenney_plaza", "courtyard", "mini_dungeon"];
  var KENNEY_BASE = "world/kenney/";

  /**
   * @typedef {{kind:'floor'|'platform'|'wall', minX:number, maxX:number, minZ:number, maxZ:number, topY:number, botY?:number}} WorldCollider
   */

  function mat(THREE, hex, opts) {
    opts = opts || {};
    return new THREE.MeshStandardMaterial({
      color: hex,
      roughness: opts.roughness != null ? opts.roughness : 0.9,
      metalness: opts.metalness != null ? opts.metalness : 0.05,
      transparent: !!opts.transparent,
      opacity: opts.opacity != null ? opts.opacity : 1,
    });
  }

  function boxMesh(THREE, size, color, pos) {
    var geo = new THREE.BoxGeometry(size.x, size.y, size.z);
    var mesh = new THREE.Mesh(geo, mat(THREE, color));
    mesh.position.set(pos.x, pos.y, pos.z);
    mesh.castShadow = false;
    mesh.receiveShadow = true;
    return mesh;
  }

  function addBoxCollider(list, pos, size, kind) {
    var hx = size.x * 0.5;
    var hy = size.y * 0.5;
    var hz = size.z * 0.5;
    list.push({
      kind: kind || "platform",
      minX: pos.x - hx,
      maxX: pos.x + hx,
      minZ: pos.z - hz,
      maxZ: pos.z + hz,
      topY: pos.y + hy,
      botY: pos.y - hy,
    });
  }

  function addBox(THREE, root, colliders, pos, size, color, collide, kind) {
    root.add(boxMesh(THREE, size, color, pos));
    if (collide) addBoxCollider(colliders, pos, size, kind || "platform");
  }

  function buildProtoArena(THREE, root, colliders) {
    // Floor 40x40, top at y=0
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: -0.2, z: 0 },
      { x: 40, y: 0.4, z: 40 },
      0x171c29,
      true,
      "floor",
    );
    var wallC = 0x2e3d57;
    var walls = [
      { x: 0, y: 0.4, z: -20, s: { x: 40, y: 1.2, z: 0.35 } },
      { x: 0, y: 0.4, z: 20, s: { x: 40, y: 1.2, z: 0.35 } },
      { x: 20, y: 0.4, z: 0, s: { x: 0.35, y: 1.2, z: 40 } },
      { x: -20, y: 0.4, z: 0, s: { x: 0.35, y: 1.2, z: 40 } },
    ];
    for (var i = 0; i < walls.length; i++) {
      var w = walls[i];
      addBox(THREE, root, colliders, { x: w.x, y: w.y, z: w.z }, w.s, wallC, true, "wall");
    }
    // Center pad
    var pad = boxMesh(THREE, { x: 3.2, y: 0.04, z: 3.2 }, 0x3d5a80, { x: 0, y: 0.02, z: 0 });
    pad.material = mat(THREE, 0x3d5a80, { roughness: 0.7, metalness: 0.12 });
    root.add(pad);
    return { spawn: { x: 0, y: 0.05, z: 0 }, worldHalf: 18 };
  }

  function buildCourtyard(THREE, root, colliders) {
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: -0.2, z: 0 },
      { x: 28, y: 0.4, z: 28 },
      0x38332e,
      true,
      "floor",
    );
    // Fountain
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 0.35, z: 0 },
      { x: 3.2, y: 0.7, z: 3.2 },
      0x737a8c,
      true,
      "platform",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 0.9, z: 0 },
      { x: 1.2, y: 0.4, z: 1.2 },
      0x808ca6,
      true,
      "platform",
    );
    var cylGeo = new THREE.CylinderGeometry(0.35, 0.35, 1.0, 16);
    var cyl = new THREE.Mesh(cylGeo, mat(THREE, 0x8cb3d9, { roughness: 0.4, metalness: 0.15 }));
    cyl.position.set(0, 1.4, 0);
    root.add(cyl);

    var colC = 0x8c806b;
    var capC = 0x7a6b5c;
    var xs = [-10, 10];
    var zs;
    var xi;
    var zi;
    for (xi = 0; xi < xs.length; xi++) {
      for (zi = -8; zi <= 8; zi += 4) {
        addBox(
          THREE,
          root,
          colliders,
          { x: xs[xi], y: 1.5, z: zi },
          { x: 0.7, y: 3.0, z: 0.7 },
          colC,
          true,
          "wall",
        );
        addBox(
          THREE,
          root,
          colliders,
          { x: xs[xi], y: 3.2, z: zi },
          { x: 1.4, y: 0.35, z: 1.4 },
          capC,
          true,
          "platform",
        );
      }
    }
    zs = [-10, 10];
    for (zi = 0; zi < zs.length; zi++) {
      for (xi = -8; xi <= 8; xi += 4) {
        addBox(
          THREE,
          root,
          colliders,
          { x: xi, y: 1.5, z: zs[zi] },
          { x: 0.7, y: 3.0, z: 0.7 },
          colC,
          true,
          "wall",
        );
      }
    }
    // Benches
    addBox(
      THREE,
      root,
      colliders,
      { x: 4, y: 0.35, z: 5 },
      { x: 2.2, y: 0.35, z: 0.55 },
      0x59381f,
      true,
      "platform",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: -4, y: 0.35, z: 5 },
      { x: 2.2, y: 0.35, z: 0.55 },
      0x59381f,
      true,
      "platform",
    );
    // Perimeter
    var wallC = 0x665c4d;
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 0.8, z: -14 },
      { x: 28, y: 1.6, z: 0.5 },
      wallC,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 0.8, z: 14 },
      { x: 28, y: 1.6, z: 0.5 },
      wallC,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: -14, y: 0.8, z: 0 },
      { x: 0.5, y: 1.6, z: 28 },
      wallC,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 14, y: 0.8, z: 0 },
      { x: 0.5, y: 1.6, z: 28 },
      wallC,
      true,
      "wall",
    );
    // Planters
    var planters = [
      { x: 6, z: -4 },
      { x: -6, z: -4 },
      { x: 6, z: 2 },
      { x: -6, z: 2 },
    ];
    for (var p = 0; p < planters.length; p++) {
      var pl = planters[p];
      addBox(
        THREE,
        root,
        colliders,
        { x: pl.x, y: 0.4, z: pl.z },
        { x: 1.4, y: 0.8, z: 1.4 },
        0x66472e,
        true,
        "platform",
      );
      addBox(
        THREE,
        root,
        colliders,
        { x: pl.x, y: 0.95, z: pl.z },
        { x: 1.1, y: 0.3, z: 1.1 },
        0x337338,
        false,
      );
    }
    return { spawn: { x: 0, y: 0.1, z: 6 }, worldHalf: 13 };
  }

  function buildMiniDungeon(THREE, root, colliders) {
    var stone = 0x474d5c;
    var floorC = 0x292b38;
    var accent = 0x8c5933;
    // Main hall floor
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: -0.15, z: 0 },
      { x: 14, y: 0.3, z: 18 },
      floorC,
      true,
      "floor",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 1.4, z: -9 },
      { x: 14, y: 2.8, z: 0.5 },
      stone,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: -7, y: 1.4, z: 0 },
      { x: 0.5, y: 2.8, z: 18 },
      stone,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 7, y: 1.4, z: 0 },
      { x: 0.5, y: 2.8, z: 18 },
      stone,
      true,
      "wall",
    );
    // Doorway gap on +Z
    addBox(
      THREE,
      root,
      colliders,
      { x: -4.2, y: 1.4, z: 9 },
      { x: 5.6, y: 2.8, z: 0.5 },
      stone,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 4.2, y: 1.4, z: 9 },
      { x: 5.6, y: 2.8, z: 0.5 },
      stone,
      true,
      "wall",
    );
    // North room
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: -0.15, z: 14 },
      { x: 10, y: 0.3, z: 8 },
      floorC,
      true,
      "floor",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 1.4, z: 18 },
      { x: 10, y: 2.8, z: 0.5 },
      stone,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: -5, y: 1.4, z: 14 },
      { x: 0.5, y: 2.8, z: 8 },
      stone,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 5, y: 1.4, z: 14 },
      { x: 0.5, y: 2.8, z: 8 },
      stone,
      true,
      "wall",
    );
    // East alcove
    addBox(
      THREE,
      root,
      colliders,
      { x: 10, y: -0.15, z: 0 },
      { x: 6, y: 0.3, z: 6 },
      floorC,
      true,
      "floor",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 10, y: 1.4, z: -3 },
      { x: 6, y: 2.8, z: 0.5 },
      stone,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 10, y: 1.4, z: 3 },
      { x: 6, y: 2.8, z: 0.5 },
      stone,
      true,
      "wall",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 13, y: 1.4, z: 0 },
      { x: 0.5, y: 2.8, z: 6 },
      stone,
      true,
      "wall",
    );
    var pillarZ = [-4, 0, 4];
    for (var i = 0; i < pillarZ.length; i++) {
      var pz = pillarZ[i];
      addBox(
        THREE,
        root,
        colliders,
        { x: -3.5, y: 1.2, z: pz },
        { x: 0.7, y: 2.4, z: 0.7 },
        stone,
        true,
        "wall",
      );
      addBox(
        THREE,
        root,
        colliders,
        { x: 3.5, y: 1.2, z: pz },
        { x: 0.7, y: 2.4, z: 0.7 },
        stone,
        true,
        "wall",
      );
    }
    addBox(
      THREE,
      root,
      colliders,
      { x: -4.5, y: 0.45, z: -6 },
      { x: 1.6, y: 0.9, z: 0.9 },
      accent,
      true,
      "platform",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 4.5, y: 0.55, z: 5 },
      { x: 1.2, y: 1.1, z: 1.2 },
      accent,
      true,
      "platform",
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 0.4, z: 15 },
      { x: 2.4, y: 0.8, z: 1.0 },
      0x664026,
      true,
      "platform",
    );
    // Ceiling plates (visual)
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 3.0, z: 0 },
      { x: 13.5, y: 0.15, z: 17.5 },
      0x1f2129,
      false,
    );
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: 3.0, z: 14 },
      { x: 9.5, y: 0.15, z: 7.5 },
      0x1f2129,
      false,
    );
    // Dim point lights via emissive markers
    function torch(x, y, z, hex) {
      var s = new THREE.Mesh(
        new THREE.SphereGeometry(0.12, 8, 8),
        new THREE.MeshBasicMaterial({ color: hex }),
      );
      s.position.set(x, y, z);
      root.add(s);
    }
    torch(-3.5, 2.2, -4, 0xffa64d);
    torch(3.5, 2.2, 4, 0xffa64d);
    torch(0, 2.0, 14, 0x99bfff);
    return { spawn: { x: 0, y: 0.15, z: 3 }, worldHalf: 14 };
  }

  function loadGltf(THREE, url) {
    return new Promise(function (resolve, reject) {
      try {
        var loader = new THREE.GLTFLoader();
        if (!loader || typeof loader.load !== "function") {
          // Bundled companion-vrm-libs exposes GLTFLoader on THREE or as export.
          if (global.THREE && global.THREE.GLTFLoader) {
            loader = new global.THREE.GLTFLoader();
          } else if (global.CompanionVrmLibs && global.CompanionVrmLibs.GLTFLoader) {
            loader = new global.CompanionVrmLibs.GLTFLoader();
          } else {
            reject(new Error("GLTFLoader unavailable"));
            return;
          }
        }
        loader.load(
          url,
          function (gltf) {
            resolve(gltf && gltf.scene ? gltf.scene : gltf);
          },
          undefined,
          function (err) {
            reject(err || new Error("gltf load failed: " + url));
          },
        );
      } catch (e) {
        reject(e);
      }
    });
  }

  function getGltfLoader(L) {
    if (L && L.GLTFLoader) return new L.GLTFLoader();
    if (L && L.THREE && L.THREE.GLTFLoader) return new L.THREE.GLTFLoader();
    if (global.THREE && global.THREE.GLTFLoader) return new global.THREE.GLTFLoader();
    if (global.CompanionVrmLibs && global.CompanionVrmLibs.GLTFLoader) {
      return new global.CompanionVrmLibs.GLTFLoader();
    }
    return null;
  }

  function loadGltfWith(L, url) {
    return new Promise(function (resolve, reject) {
      var loader = getGltfLoader(L);
      if (!loader) {
        reject(new Error("GLTFLoader unavailable"));
        return;
      }
      loader.load(
        url,
        function (gltf) {
          resolve(gltf && gltf.scene ? gltf.scene.clone(true) : null);
        },
        undefined,
        function (err) {
          reject(err || new Error("load " + url));
        },
      );
    });
  }

  function assetUrl(rel) {
    // Stage is loaded from file:///android_asset/companion/index.html
    // Relative paths resolve under companion/.
    return rel;
  }

  function buildKenneyPlaza(THREE, L, root, colliders) {
    // Ground
    addBox(
      THREE,
      root,
      colliders,
      { x: 0, y: -0.2, z: 0 },
      { x: 48, y: 0.4, z: 48 },
      0x1f4733,
      true,
      "floor",
    );
    var walls = [
      { x: 0, y: 0.6, z: -18, s: { x: 36, y: 1.4, z: 0.4 } },
      { x: 0, y: 0.6, z: 18, s: { x: 36, y: 1.4, z: 0.4 } },
      { x: -18, y: 0.6, z: 0, s: { x: 0.4, y: 1.4, z: 36 } },
      { x: 18, y: 0.6, z: 0, s: { x: 0.4, y: 1.4, z: 36 } },
    ];
    for (var i = 0; i < walls.length; i++) {
      var w = walls[i];
      addBox(
        THREE,
        root,
        colliders,
        { x: w.x, y: w.y, z: w.z },
        w.s,
        0x335938,
        true,
        "wall",
      );
    }

    var props = [
      { file: "platform-large.glb", pos: [0, 0, 0], scale: 1.0, platform: true },
      { file: "platform-grass-large-round.glb", pos: [0, 0.02, 0], scale: 1.1, platform: true },
      { file: "platform-medium.glb", pos: [4.5, 0.6, -2.5], scale: 1.0, platform: true },
      { file: "platform-medium.glb", pos: [-4.2, 0.9, -1.2], scale: 1.0, platform: true },
      { file: "platform.glb", pos: [2.2, 1.4, -5.5], scale: 1.0, platform: true },
      { file: "platform.glb", pos: [-1.5, 1.8, -6.2], scale: 1.0, platform: true },
      { file: "platform-large.glb", pos: [0, 2.2, -9.0], scale: 0.7, platform: true },
      { file: "grass.glb", pos: [3.2, 0.05, 2.4], scale: 1.2 },
      { file: "grass-small.glb", pos: [-2.8, 0.05, 1.6], scale: 1.0 },
      { file: "grass.glb", pos: [-5.5, 0.05, -3.0], scale: 1.0 },
      { file: "flag.glb", pos: [0, 2.35, -9.0], scale: 1.0 },
      { file: "cloud.glb", pos: [6, 5.5, -4], scale: 1.4 },
      { file: "cloud.glb", pos: [-7, 6.2, -8], scale: 1.2 },
      { file: "block-coin.glb", pos: [2.2, 2.1, -5.5], scale: 1.0 },
      { file: "coin.glb", pos: [-1.5, 2.5, -6.2], scale: 1.0 },
    ];

    var jobs = props.map(function (p) {
      var url = assetUrl(KENNEY_BASE + p.file);
      return loadGltfWith(L, url)
        .then(function (scene) {
          if (!scene) return;
          scene.position.set(p.pos[0], p.pos[1], p.pos[2]);
          scene.scale.setScalar(p.scale);
          root.add(scene);
          if (p.platform) {
            var sc = p.scale;
            addBoxCollider(
              colliders,
              { x: p.pos[0], y: p.pos[1] + 0.15 * sc, z: p.pos[2] },
              { x: 2.4 * sc, y: 0.35 * sc, z: 2.4 * sc },
              "platform",
            );
          }
        })
        .catch(function () {
          // Fallback boxes if GLB missing
          if (p.platform) {
            addBox(
              THREE,
              root,
              colliders,
              { x: p.pos[0], y: p.pos[1] + 0.15 * p.scale, z: p.pos[2] },
              { x: 2.4 * p.scale, y: 0.35 * p.scale, z: 2.4 * p.scale },
              0x5a8c4a,
              true,
              "platform",
            );
          }
        });
    });

    return Promise.all(jobs).then(function () {
      return { spawn: { x: 0, y: 0.1, z: 4 }, worldHalf: 20 };
    });
  }

  /**
   * Build a map into `root`. Clears nothing — caller clears.
   * @returns {Promise<{spawn:{x,y,z}, worldHalf:number}>}
   */
  function buildMap(mapId, THREE, L, root, colliders) {
    var id = String(mapId || "proto_arena").trim();
    if (id === "kenney_plaza") {
      return buildKenneyPlaza(THREE, L, root, colliders);
    }
    if (id === "courtyard") {
      return Promise.resolve(buildCourtyard(THREE, root, colliders));
    }
    if (id === "mini_dungeon") {
      return Promise.resolve(buildMiniDungeon(THREE, root, colliders));
    }
    return Promise.resolve(buildProtoArena(THREE, root, colliders));
  }

  /**
   * Sample standable ground under (x,z). Prefers highest topY the player can land on
   * from fromY (feet), with a small step-up allowance.
   */
  function sampleGroundY(colliders, x, z, fromY, stepUp) {
    stepUp = stepUp == null ? 0.45 : stepUp;
    var best = 0;
    var found = false;
    if (!colliders || !colliders.length) return 0;
    for (var i = 0; i < colliders.length; i++) {
      var c = colliders[i];
      if (c.kind === "wall") continue;
      if (x < c.minX || x > c.maxX || z < c.minZ || z > c.maxZ) continue;
      var top = c.topY;
      // Can stand if feet are above or within step-up of surface, not deep inside.
      if (fromY + stepUp >= top && fromY >= top - 1.2) {
        if (!found || top > best) {
          best = top;
          found = true;
        }
      }
    }
    return found ? best : 0;
  }

  /** Simple AABB wall resolve for horizontal motion. */
  function resolveWalls(colliders, x, z, y, radius) {
    radius = radius == null ? 0.28 : radius;
    var nx = x;
    var nz = z;
    if (!colliders) return { x: nx, z: nz };
    for (var i = 0; i < colliders.length; i++) {
      var c = colliders[i];
      if (c.kind !== "wall" && c.kind !== "platform") continue;
      // Only block if capsule height overlaps solid.
      var bot = c.botY != null ? c.botY : c.topY - 0.5;
      if (y + 1.5 < bot || y > c.topY + 0.05) continue;
      if (c.kind === "platform" && y >= c.topY - 0.05) continue; // standing on it
      var minX = c.minX - radius;
      var maxX = c.maxX + radius;
      var minZ = c.minZ - radius;
      var maxZ = c.maxZ + radius;
      if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
      // Push out along smallest penetration axis.
      var penL = nx - minX;
      var penR = maxX - nx;
      var penD = nz - minZ;
      var penU = maxZ - nz;
      var m = Math.min(penL, penR, penD, penU);
      if (m === penL) nx = minX;
      else if (m === penR) nx = maxX;
      else if (m === penD) nz = minZ;
      else nz = maxZ;
    }
    return { x: nx, z: nz };
  }

  global.CompanionWorldMaps = {
    MAP_IDS: MAP_IDS,
    listMaps: function () {
      return MAP_IDS.slice();
    },
    buildMap: buildMap,
    sampleGroundY: sampleGroundY,
    resolveWalls: resolveWalls,
  };
})(typeof window !== "undefined" ? window : globalThis);
