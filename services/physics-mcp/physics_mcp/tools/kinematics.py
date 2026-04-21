import math
from fastmcp import FastMCP

mcp = FastMCP()


@mcp.tool()
def kinetic_energy(mass_kg: float, velocity_ms: float) -> dict:
    """
    Calculate classical kinetic energy: KE = 0.5 * m * v^2.

    Args:
        mass_kg: Mass in kilograms.
        velocity_ms: Velocity in metres per second.
    """
    ke = 0.5 * mass_kg * velocity_ms ** 2
    return {
        "energy_joules": ke,
        "formula": "KE = 0.5 * m * v^2",
        "inputs": {"mass_kg": mass_kg, "velocity_ms": velocity_ms},
    }


@mcp.tool()
def projectile_range(
    initial_velocity_ms: float,
    angle_degrees: float,
    g_ms2: float = 9.80665,
) -> dict:
    """
    Calculate horizontal range of a projectile in a vacuum.

    Args:
        initial_velocity_ms: Launch speed in m/s.
        angle_degrees: Launch angle above horizontal in degrees.
        g_ms2: Gravitational acceleration (default: standard g = 9.80665 m/s^2).
    """
    angle_rad = math.radians(angle_degrees)
    v0 = initial_velocity_ms
    range_m = (v0 ** 2 * math.sin(2 * angle_rad)) / g_ms2
    tof = (2 * v0 * math.sin(angle_rad)) / g_ms2
    return {
        "range_metres": range_m,
        "time_of_flight_s": tof,
        "formula": "R = v0^2 * sin(2θ) / g",
        "inputs": {
            "initial_velocity_ms": v0,
            "angle_degrees": angle_degrees,
            "g_ms2": g_ms2,
        },
    }
