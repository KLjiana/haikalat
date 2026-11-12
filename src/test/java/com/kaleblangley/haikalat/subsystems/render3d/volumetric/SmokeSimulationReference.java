package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Vector3f;

/**
 * Independent double-precision reference for the bounded smoke grid.
 *
 * <p>This class is a second implementation of the scheme documented in
 * {@link SmokeSimulationSettings} and implemented by
 * {@code smoke-step.comp}: cell velocity
 * {@code wind + (0, buoyancy * temperature, 0)} with temperature clamped to
 * {@code [0, maximumTemperature]}, mean face velocities, exactly zero normal
 * velocity on every outer face, first-order upwind scalar fluxes, a Jacobi
 * update against the old field, exact exponential dissipation and the same
 * {@code (1 - q)^2} injection falloff. It deliberately does not call any
 * production helper. The {@link #step} method advances exactly one fixed step
 * for a given committed step index so the source windows are evaluated at the
 * same simulation time as {@link SmokeTimeline}.</p>
 *
 * <p>The fixed expected values in {@code SmokeSimulationReferenceTest} were
 * computed from a separate scratch calculation, not by calling this class.</p>
 */
final class SmokeSimulationReference {
    private SmokeSimulationReference() {
    }

    /** Mutable double field pair with deterministic indexing. */
    static final class Grid {
        private final int nx;
        private final int ny;
        private final int nz;
        private final double[] density;
        private final double[] temperature;

        Grid(int nx, int ny, int nz) {
            if (nx <= 0 || ny <= 0 || nz <= 0) {
                throw new IllegalArgumentException("grid extents must be positive");
            }
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            density = new double[Math.multiplyExact(Math.multiplyExact(nx, ny), nz)];
            temperature = new double[density.length];
        }

        int nx() {
            return nx;
        }

        int ny() {
            return ny;
        }

        int nz() {
            return nz;
        }

        int index(int x, int y, int z) {
            if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) {
                throw new IllegalArgumentException("cell outside the grid");
            }
            return x + nx * (y + ny * z);
        }

        double density(int x, int y, int z) {
            return density[index(x, y, z)];
        }

        double temperature(int x, int y, int z) {
            return temperature[index(x, y, z)];
        }

        void set(int x, int y, int z, double densityValue, double temperatureValue) {
            int at = index(x, y, z);
            density[at] = densityValue;
            temperature[at] = temperatureValue;
        }

        double totalDensity() {
            double total = 0.0;
            for (double value : density) {
                total += value;
            }
            return total;
        }

        double maximumDensity() {
            double maximum = 0.0;
            for (double value : density) {
                maximum = Math.max(maximum, value);
            }
            return maximum;
        }

        double maximumTemperature() {
            double maximum = 0.0;
            for (double value : temperature) {
                maximum = Math.max(maximum, value);
            }
            return maximum;
        }

        double[] densityCopy() {
            return density.clone();
        }

        double[] temperatureCopy() {
            return temperature.clone();
        }

        private double densityAtClamped(int x, int y, int z) {
            return density[index(clamp(x, nx), clamp(y, ny), clamp(z, nz))];
        }

        private double temperatureAtClamped(int x, int y, int z) {
            return temperature[index(clamp(x, nx), clamp(y, ny), clamp(z, nz))];
        }
    }

    /** @return an empty reference grid matching the settings extents */
    static Grid empty(SmokeSimulationSettings settings) {
        return new Grid(settings.gridWidth(), settings.gridHeight(), settings.gridDepth());
    }

    /**
     * Seeds a smooth spherical blob; only test fixtures use this, the production
     * initial state is the zero field produced by {@code reset}.
     */
    static void seedSphere(Grid grid, SmokeSimulationSettings settings,
                           double centerX, double centerY, double centerZ, double radius,
                           double densityValue, double temperatureValue) {
        Vector3f minimum = settings.gridMinimum();
        Vector3f cell = settings.cellSize();
        for (int z = 0; z < grid.nz(); z++) {
            for (int y = 0; y < grid.ny(); y++) {
                for (int x = 0; x < grid.nx(); x++) {
                    double worldX = minimum.x + (x + 0.5) * cell.x;
                    double worldY = minimum.y + (y + 0.5) * cell.y;
                    double worldZ = minimum.z + (z + 0.5) * cell.z;
                    double dx = worldX - centerX;
                    double dy = worldY - centerY;
                    double dz = worldZ - centerZ;
                    double q = (dx * dx + dy * dy + dz * dz) / (radius * radius);
                    if (q < 1.0) {
                        double falloff = 1.0 - q;
                        double weight = falloff * falloff;
                        int at = grid.index(x, y, z);
                        grid.density[at] = densityValue * weight;
                        grid.temperature[at] = temperatureValue * weight;
                    }
                }
            }
        }
    }

    /** Advances exactly one fixed step at {@code stepIndex * timeStepSeconds}. */
    static void step(Grid grid, SmokeSimulationSettings settings, long stepIndex) {
        Vector3f half = settings.boundsHalfExtent();
        Vector3f minimum = settings.gridMinimum();
        Vector3f wind = settings.windVelocity();
        Vector3f cell = settings.cellSize();
        double dt = settings.timeStepSeconds();
        double maximumTemperature = settings.maximumTemperature();
        double buoyancy = settings.buoyancy();
        double densityDecay = Math.exp(-settings.densityDissipation() * dt);
        double temperatureDecay = Math.exp(-settings.temperatureDissipation() * dt);
        double time = stepIndex * dt;

        double[] nextDensity = new double[grid.density.length];
        double[] nextTemperature = new double[grid.temperature.length];
        for (int z = 0; z < grid.nz(); z++) {
            for (int y = 0; y < grid.ny(); y++) {
                for (int x = 0; x < grid.nx(); x++) {
                    double density = grid.densityAtClamped(x, y, z);
                    double temperature = clamp(grid.temperatureAtClamped(x, y, z),
                            0.0, maximumTemperature);
                    double velocityY = wind.y + buoyancy * temperature;

                    double leftX = grid.densityAtClamped(x - 1, y, z);
                    double rightX = grid.densityAtClamped(x + 1, y, z);
                    double leftY = grid.densityAtClamped(x, y - 1, z);
                    double rightY = grid.densityAtClamped(x, y + 1, z);
                    double leftZ = grid.densityAtClamped(x, y, z - 1);
                    double rightZ = grid.densityAtClamped(x, y, z + 1);

                    double temperatureLeftX = clamp(grid.temperatureAtClamped(x - 1, y, z),
                            0.0, maximumTemperature);
                    double temperatureRightX = clamp(grid.temperatureAtClamped(x + 1, y, z),
                            0.0, maximumTemperature);
                    double temperatureLeftY = clamp(grid.temperatureAtClamped(x, y - 1, z),
                            0.0, maximumTemperature);
                    double temperatureRightY = clamp(grid.temperatureAtClamped(x, y + 1, z),
                            0.0, maximumTemperature);
                    double temperatureLeftZ = clamp(grid.temperatureAtClamped(x, y, z - 1),
                            0.0, maximumTemperature);
                    double temperatureRightZ = clamp(grid.temperatureAtClamped(x, y, z + 1),
                            0.0, maximumTemperature);

                    double velocityXLeft = x > 0 ? wind.x : 0.0;
                    double velocityXRight = x + 1 < grid.nx() ? wind.x : 0.0;
                    double velocityZLeft = z > 0 ? wind.z : 0.0;
                    double velocityZRight = z + 1 < grid.nz() ? wind.z : 0.0;
                    double velocityYLeft = 0.0;
                    double velocityYRight = 0.0;
                    if (y > 0) {
                        double neighbour = wind.y + buoyancy * temperatureLeftY;
                        velocityYLeft = 0.5 * (neighbour + velocityY);
                    }
                    if (y + 1 < grid.ny()) {
                        double neighbour = wind.y + buoyancy * temperatureRightY;
                        velocityYRight = 0.5 * (velocityY + neighbour);
                    }

                    double densityFluxX = upwind(velocityXRight, density, rightX)
                            - upwind(velocityXLeft, leftX, density);
                    double densityFluxY = upwind(velocityYRight, density, rightY)
                            - upwind(velocityYLeft, leftY, density);
                    double densityFluxZ = upwind(velocityZRight, density, rightZ)
                            - upwind(velocityZLeft, leftZ, density);
                    double temperatureFluxX = upwind(velocityXRight, temperature,
                            temperatureRightX) - upwind(velocityXLeft, temperatureLeftX,
                            temperature);
                    double temperatureFluxY = upwind(velocityYRight, temperature,
                            temperatureRightY) - upwind(velocityYLeft, temperatureLeftY,
                            temperature);
                    double temperatureFluxZ = upwind(velocityZRight, temperature,
                            temperatureRightZ) - upwind(velocityZLeft, temperatureLeftZ,
                            temperature);

                    double divergenceDensity = densityFluxX / cell.x
                            + densityFluxY / cell.y + densityFluxZ / cell.z;
                    double divergenceTemperature = temperatureFluxX / cell.x
                            + temperatureFluxY / cell.y + temperatureFluxZ / cell.z;

                    double densityNext = (density - dt * divergenceDensity) * densityDecay;
                    double temperatureNext = (temperature - dt * divergenceTemperature)
                            * temperatureDecay;

                    double worldX = minimum.x + (x + 0.5) * cell.x;
                    double worldY = minimum.y + (y + 0.5) * cell.y;
                    double worldZ = minimum.z + (z + 0.5) * cell.z;
                    for (SmokeSimulationSettings.Source source : settings.sources()) {
                        if (!source.activeAt(time)) {
                            continue;
                        }
                        double weight = sourceWeight(source, worldX, worldY, worldZ);
                        densityNext += weight * source.densityRate() * dt;
                        temperatureNext += weight * source.temperatureRate() * dt;
                    }
                    if (!Double.isFinite(densityNext) || densityNext < 0.0) {
                        densityNext = 0.0;
                    }
                    if (!Double.isFinite(temperatureNext)) {
                        temperatureNext = 0.0;
                    }
                    temperatureNext = clamp(temperatureNext, 0.0, maximumTemperature);
                    int at = grid.index(x, y, z);
                    nextDensity[at] = densityNext;
                    nextTemperature[at] = temperatureNext;
                }
            }
        }
        System.arraycopy(nextDensity, 0, grid.density, 0, nextDensity.length);
        System.arraycopy(nextTemperature, 0, grid.temperature, 0, nextTemperature.length);
    }

    /** The shader's smooth source falloff at a world position. */
    static double sourceWeight(SmokeSimulationSettings.Source source,
                               double worldX, double worldY, double worldZ) {
        double radius = source.radius();
        if (!(radius > 0.0)) {
            return 0.0;
        }
        Vector3f center = source.center();
        double dx = worldX - center.x;
        double dy = worldY - center.y;
        double dz = worldZ - center.z;
        double q = (dx * dx + dy * dy + dz * dz) / (radius * radius);
        if (!(q < 1.0)) {
            return 0.0;
        }
        double falloff = 1.0 - q;
        return falloff * falloff;
    }

    private static double upwind(double velocity, double left, double right) {
        return velocity > 0.0 ? velocity * left : velocity * right;
    }

    private static int clamp(int value, int extent) {
        return Math.max(0, Math.min(extent - 1, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
