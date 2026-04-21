"""
Physical constants tools.

Values are CODATA/PDG placeholders. These will be replaced by the Skippy
cascade (constants.py from Sigma Ground) when that work is integrated.
Tag: MEASURED — citations to CODATA 2018 / PDG 2022.
"""

from fastmcp import FastMCP

mcp = FastMCP()

_CONSTANTS = {
    "c":    {"value": 299_792_458.0,      "unit": "m/s",           "description": "Speed of light in vacuum"},
    "h":    {"value": 6.62607015e-34,     "unit": "J·s",           "description": "Planck constant"},
    "hbar": {"value": 1.054571817e-34,    "unit": "J·s",           "description": "Reduced Planck constant"},
    "G":    {"value": 6.67430e-11,        "unit": "m^3 kg^-1 s^-2","description": "Gravitational constant"},
    "k_B":  {"value": 1.380649e-23,       "unit": "J/K",           "description": "Boltzmann constant"},
    "e":    {"value": 1.602176634e-19,    "unit": "C",             "description": "Elementary charge"},
    "m_p":  {"value": 1.67262192369e-27,  "unit": "kg",            "description": "Proton mass"},
    "m_e":  {"value": 9.1093837015e-31,   "unit": "kg",            "description": "Electron mass"},
    "m_n":  {"value": 1.67492749804e-27,  "unit": "kg",            "description": "Neutron mass"},
}


@mcp.tool()
def get_constant(name: str) -> dict:
    """
    Retrieve a physical constant by symbol.

    Args:
        name: Symbol such as 'c', 'h', 'G', 'm_p', 'k_B'.
    """
    if name in _CONSTANTS:
        return _CONSTANTS[name]
    return {"error": f"Unknown constant '{name}'", "available": list(_CONSTANTS.keys())}


@mcp.tool()
def list_constants() -> dict:
    """List all available physical constants with their units and descriptions."""
    return {
        sym: {"unit": d["unit"], "description": d["description"]}
        for sym, d in _CONSTANTS.items()
    }
