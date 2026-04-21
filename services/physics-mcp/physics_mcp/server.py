"""
Physics MCP Server entry point.

Claude Desktop connects via stdio — this process is launched as a subprocess.

Run via:
    python -m physics_mcp.server
    physics-mcp                     # if installed via pip install -e .
"""

from fastmcp import FastMCP
from physics_mcp.tools import kinematics, constants

mcp = FastMCP(
    name="physics-mcp",
    instructions=(
        "You are connected to a physics calculation server. "
        "Use the available tools to perform accurate calculations from first principles. "
        "All constants will eventually come from the Skippy cascade (Sigma Ground)."
    ),
)

mcp.include_module(kinematics)
mcp.include_module(constants)


def run():
    mcp.run()  # defaults to stdio transport


if __name__ == "__main__":
    run()
