import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.module.js';

const Plasma = (() => {
  let renderer, scene, camera, points, geo;
  let animId = null;
  let clock;
  const N = 10000;

  function createNoise3D() {
    const perm = new Uint8Array(512);
    const p = new Uint8Array(256);
    for (let i = 0; i < 256; i++) p[i] = i;
    for (let i = 255; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [p[i], p[j]] = [p[j], p[i]];
    }
    for (let i = 0; i < 512; i++) perm[i] = p[i & 255];

    return function(x, y, z) {
      const X = Math.floor(x) & 255, Y = Math.floor(y) & 255, Z = Math.floor(z) & 255;
      x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
      const u = x * x * (3 - 2 * x), v = y * y * (3 - 2 * y), w = z * z * (3 - 2 * z);
      const A = perm[X] + Y, AA = perm[A] + Z, AB = perm[A + 1] + Z;
      const B = perm[X + 1] + Y, BA = perm[B] + Z, BB = perm[B + 1] + Z;
      const grad = (hash, x, y, z) => {
        const h = hash & 15;
        const u = h < 8 ? x : y, v2 = h < 4 ? y : h === 12 || h === 14 ? x : z;
        return ((h & 1) === 0 ? u : -u) + ((h & 2) === 0 ? v2 : -v2);
      };
      const lerp = (t, a, b) => a + t * (b - a);
      return lerp(w,
        lerp(v,
          lerp(u, grad(perm[AA], x, y, z), grad(perm[BA], x - 1, y, z)),
          lerp(u, grad(perm[AB], x, y - 1, z), grad(perm[BB], x - 1, y - 1, z))),
        lerp(v,
          lerp(u, grad(perm[AA + 1], x, y, z - 1), grad(perm[BA + 1], x - 1, y, z - 1)),
          lerp(u, grad(perm[AB + 1], x, y - 1, z - 1), grad(perm[BB + 1], x - 1, y - 1, z - 1))));
    };
  }

  let noise3D;

  function init(canvasEl) {
    clock = new THREE.Clock();
    noise3D = createNoise3D();

    const parent = canvasEl.parentElement;
    const rect = parent.getBoundingClientRect();
    const W = rect.width || 400;
    const H = rect.height || 400;

    canvasEl.remove();

    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setSize(W, H);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setClearColor(0x000000, 0);
    parent.appendChild(renderer.domElement);
    renderer.domElement.style.display = 'block';
    renderer.domElement.style.width = '100%';
    renderer.domElement.style.height = '100%';

    scene = new THREE.Scene();

    camera = new THREE.PerspectiveCamera(45, W / H, 0.1, 100);
    camera.position.z = 4.5;

    // Particles
    geo = new THREE.BufferGeometry();
    const positions = new Float32Array(N * 3);
    const basePositions = new Float32Array(N * 3);

    for (let i = 0; i < N; i++) {
      const phi = Math.acos(1 - 2 * Math.random());
      const theta = Math.random() * Math.PI * 2;
      const r = 1.2;
      const x = r * Math.sin(phi) * Math.cos(theta);
      const y = r * Math.sin(phi) * Math.sin(theta);
      const z = r * Math.cos(phi);

      positions[i * 3] = x;
      positions[i * 3 + 1] = y;
      positions[i * 3 + 2] = z;
      basePositions[i * 3] = x;
      basePositions[i * 3 + 1] = y;
      basePositions[i * 3 + 2] = z;
    }

    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    geo.setAttribute('basePosition', new THREE.BufferAttribute(basePositions, 3));

    const sizes = new Float32Array(N);
    for (let i = 0; i < N; i++) {
      sizes[i] = 0.012 + Math.random() * 0.032;
    }
    geo.setAttribute('size', new THREE.BufferAttribute(sizes, 1));

    const pointMat = new THREE.ShaderMaterial({
      uniforms: {
        uColor: { value: new THREE.Color(0x2563eb) },
      },
      vertexShader: `
        attribute float size;
        void main() {
          vec4 mvPos = modelViewMatrix * vec4(position, 1.0);
          gl_PointSize = size * (400.0 / -mvPos.z);
          gl_Position = projectionMatrix * mvPos;
        }
      `,
      fragmentShader: `
        uniform vec3 uColor;
        void main() {
          float d = length(gl_PointCoord - vec2(0.5));
          if (d > 0.5) discard;
          gl_FragColor = vec4(uColor, 1.0);
        }
      `,
      transparent: true,
    });

    points = new THREE.Points(geo, pointMat);
    scene.add(points);

    window.addEventListener('resize', () => {
      const r = parent.getBoundingClientRect();
      const w = r.width || 400;
      const h = r.height || 400;
      renderer.setSize(w, h);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    });

    animate();
  }

  function animate() {
    animId = requestAnimationFrame(animate);
    const elapsed = clock.getElapsedTime();

    const pos = geo.attributes.position.array;
    const base = geo.attributes.basePosition.array;

    const speed = 0.3;
    const noiseScale = 1.2;
    const noiseStrength = 0.5;
    const tangentStrength = 0.35;

    for (let i = 0; i < N; i++) {
      const bx = base[i * 3];
      const by = base[i * 3 + 1];
      const bz = base[i * 3 + 2];

      // Radial noise (breathing)
      const n = noise3D(
        bx * noiseScale + elapsed * speed,
        by * noiseScale + elapsed * speed * 0.7,
        bz * noiseScale + elapsed * speed * 0.5
      );
      const displacement = 1 + n * noiseStrength;

      // Tangential noise (particles drift across surface)
      const tx = noise3D(
        bx * noiseScale * 1.5 + 100 + elapsed * speed * 0.5,
        by * noiseScale * 1.5 + 100,
        bz * noiseScale * 1.5 + 100
      );
      const ty = noise3D(
        bx * noiseScale * 1.5 + 200,
        by * noiseScale * 1.5 + 200 + elapsed * speed * 0.5,
        bz * noiseScale * 1.5 + 200
      );
      const tz = noise3D(
        bx * noiseScale * 1.5 + 300,
        by * noiseScale * 1.5 + 300,
        bz * noiseScale * 1.5 + 300 + elapsed * speed * 0.5
      );

      let px = bx * displacement + tx * tangentStrength;
      let py = by * displacement + ty * tangentStrength;
      let pz = bz * displacement + tz * tangentStrength;

      // Re-project onto shell to keep spherical shape
      const len = Math.sqrt(px * px + py * py + pz * pz);
      const targetR = 1.2 * displacement;
      pos[i * 3] = (px / len) * targetR;
      pos[i * 3 + 1] = (py / len) * targetR;
      pos[i * 3 + 2] = (pz / len) * targetR;
    }

    geo.attributes.position.needsUpdate = true;

    // Slow rotation
    points.rotation.y = elapsed * 0.15;
    points.rotation.x = Math.sin(elapsed * 0.1) * 0.2;

    renderer.render(scene, camera);
  }

  function destroy() {
    if (animId) cancelAnimationFrame(animId);
    if (renderer) renderer.dispose();
  }

  return { init, destroy };
})();

export default Plasma;
