#!/bin/bash

# Force ALL icons to be circular - both regular and round
# This ensures no matter which icon the launcher picks, it gets a circle

echo "Making ALL icons circular..."

mkdir -p temp_force_circular

# Extract the white MTB rider
convert icon.png -fuzz 20% -transparent '#FF9800' temp_force_circular/rider_only.png

# Create circular icons for all sizes (both regular and round will be identical)
for size in 48 72 96 144 192; do
    # Create circular mask
    convert -size ${size}x${size} xc:none -fill white -draw "circle $((size/2)),$((size/2)) $((size/2)),0" temp_force_circular/mask_${size}.png
    
    # Create orange circle
    convert -size ${size}x${size} xc:'#FF9800' temp_force_circular/mask_${size}.png -compose DstIn -composite temp_force_circular/orange_circle_${size}.png
    
    # Size the rider to fill 70% of the circle
    rider_size=$((size * 70 / 100))
    
    # Composite rider onto orange circle
    convert temp_force_circular/orange_circle_${size}.png \
        \( temp_force_circular/rider_only.png -resize ${rider_size}x${rider_size} \) \
        -gravity center -compose Over -composite \
        temp_force_circular/circular_${size}.png
done

# Convert to WebP and replace BOTH regular and round icons
cwebp -q 100 -exact temp_force_circular/circular_48.png -o app/src/main/res/mipmap-mdpi/ic_launcher.webp
cwebp -q 100 -exact temp_force_circular/circular_72.png -o app/src/main/res/mipmap-hdpi/ic_launcher.webp
cwebp -q 100 -exact temp_force_circular/circular_96.png -o app/src/main/res/mipmap-xhdpi/ic_launcher.webp
cwebp -q 100 -exact temp_force_circular/circular_144.png -o app/src/main/res/mipmap-xxhdpi/ic_launcher.webp
cwebp -q 100 -exact temp_force_circular/circular_192.png -o app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp

cwebp -q 100 -exact temp_force_circular/circular_48.png -o app/src/main/res/mipmap-mdpi/ic_launcher_round.webp
cwebp -q 100 -exact temp_force_circular/circular_72.png -o app/src/main/res/mipmap-hdpi/ic_launcher_round.webp
cwebp -q 100 -exact temp_force_circular/circular_96.png -o app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp
cwebp -q 100 -exact temp_force_circular/circular_144.png -o app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp
cwebp -q 100 -exact temp_force_circular/circular_192.png -o app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp

# Clean up
rm -rf temp_force_circular

echo "All icons are now identical circles!"
echo "Launcher cannot add white background to an already-circular icon."