#!/usr/bin/env bash
set -euo pipefail

if [[ "$(id -u)" == "0" ]]; then
  echo "Run this script as your normal sudo-enabled user, not as root." >&2
  exit 2
fi
source /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "This installer supports Ubuntu 22.04, 24.04 and 26.04 only." >&2
  exit 2
fi
command -v nvidia-smi >/dev/null || {
  echo "Install the NVIDIA driver with Ubuntu's package manager, reboot, and confirm nvidia-smi works first." >&2
  exit 1
}

sudo apt-get update
sudo apt-get install -y --no-install-recommends ca-certificates curl gnupg2 openssl python3 python3-venv

if ! command -v docker >/dev/null; then
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  sudo chmod a+r /etc/apt/keyrings/docker.asc
  sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  sudo apt-get update
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

if ! command -v nvidia-ctk >/dev/null; then
  curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey |
    sudo gpg --dearmor --yes -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
  curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list |
    sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' |
    sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list >/dev/null
  sudo apt-get update
  NVIDIA_CONTAINER_TOOLKIT_VERSION="${NVIDIA_CONTAINER_TOOLKIT_VERSION:-1.19.1-1}"
  sudo apt-get install -y \
    "nvidia-container-toolkit=$NVIDIA_CONTAINER_TOOLKIT_VERSION" \
    "nvidia-container-toolkit-base=$NVIDIA_CONTAINER_TOOLKIT_VERSION" \
    "libnvidia-container-tools=$NVIDIA_CONTAINER_TOOLKIT_VERSION" \
    "libnvidia-container1=$NVIDIA_CONTAINER_TOOLKIT_VERSION"
fi

sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
sudo usermod -aG docker "$USER"

echo "Host runtime installed. Sign out and back in once so Docker group membership takes effect."
echo "Then run: bash scripts/gpu/check-host.sh"
