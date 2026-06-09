package com.example.cppr;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;
import java.util.ArrayList;

public class RiskData implements Parcelable {
    private String typeEn;
    private String typeZh;
    private String severity;
    private String iconName;
    private String message1;
    private String message2;
    private String PPOriginal;
    private List<BoundingBox> coordinates;
    private int statusIndicatorDrawable;
    private int functionIconResId;
    private int functionIconTint;
    private int backgroundResId;
    private int statusIconTint;
    private BoundingBox.RiskLevel globalRiskLevel;

    public RiskData() {
        coordinates = new ArrayList<>();
        globalRiskLevel = BoundingBox.RiskLevel.MEDIUM;
    }

    public RiskData(String typeEn, String typeZh, String severity, String iconName,
                   String message1, String message2, String PPOriginal, List<BoundingBox> coordinates) {
        this.typeEn = typeEn;
        this.typeZh = typeZh;
        this.severity = severity;
        this.iconName = iconName;
        this.message1 = message1;
        this.message2 = message2;
        this.PPOriginal = PPOriginal;
        this.coordinates = coordinates != null ? coordinates : new ArrayList<>();
        this.globalRiskLevel = BoundingBox.RiskLevel.MEDIUM;
    }

    protected RiskData(Parcel in) {
        typeEn = in.readString();
        typeZh = in.readString();
        severity = in.readString();
        iconName = in.readString();
        message1 = in.readString();
        message2 = in.readString();
        PPOriginal = in.readString();
        coordinates = in.createTypedArrayList(BoundingBox.CREATOR);
        statusIndicatorDrawable = in.readInt();
        functionIconResId = in.readInt();
        functionIconTint = in.readInt();
        backgroundResId = in.readInt();
        statusIconTint = in.readInt();
        String riskLevelStr = in.readString();
        try {
            globalRiskLevel = BoundingBox.RiskLevel.valueOf(riskLevelStr);
        } catch (Exception e) {
            globalRiskLevel = BoundingBox.RiskLevel.MEDIUM;
        }
    }

    public static final Creator<RiskData> CREATOR = new Creator<RiskData>() {
        @Override
        public RiskData createFromParcel(Parcel in) {
            return new RiskData(in);
        }

        @Override
        public RiskData[] newArray(int size) {
            return new RiskData[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(typeEn);
        dest.writeString(typeZh);
        dest.writeString(severity);
        dest.writeString(iconName);
        dest.writeString(message1);
        dest.writeString(message2);
        dest.writeString(PPOriginal);
        dest.writeTypedList(coordinates);
        dest.writeInt(statusIndicatorDrawable);
        dest.writeInt(functionIconResId);
        dest.writeInt(functionIconTint);
        dest.writeInt(backgroundResId);
        dest.writeInt(statusIconTint);
        dest.writeString(globalRiskLevel.name());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public String getTypeEn() { return typeEn != null ? typeEn : "Unknown"; }
    public void setTypeEn(String typeEn) { this.typeEn = typeEn; }

    public String getTypeZh() { return typeZh != null ? typeZh : "Unknown Risk"; }
    public void setTypeZh(String typeZh) { this.typeZh = typeZh; }

    public String getSeverity() { return severity != null ? severity : "medium"; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getIconName() { return iconName != null ? iconName : "ic_other"; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    public String getMessage1() { return message1 != null ? message1 : ""; }
    public void setMessage1(String message1) { this.message1 = message1; }

    public String getMessage2() { return message2 != null ? message2 : ""; }
    public void setMessage2(String message2) { this.message2 = message2; }

    public String getPPOriginal() { return PPOriginal != null ? PPOriginal : ""; }
    public void setPPOriginal(String PPOriginal) { this.PPOriginal = PPOriginal; }

    public List<BoundingBox> getCoordinates() { return coordinates != null ? coordinates : new ArrayList<>(); }
    public void setCoordinates(List<BoundingBox> coordinates) {
        this.coordinates = coordinates != null ? coordinates : new ArrayList<>();
    }

    public int getStatusIndicatorDrawable() { return statusIndicatorDrawable; }
    public int getFunctionIconResId() { return functionIconResId; }
    public int getFunctionIconTint() { return functionIconTint; }
    public int getBackgroundResId() { return backgroundResId; }
    public int getStatusIconTint() { return statusIconTint; }

    public BoundingBox.RiskLevel getGlobalRiskLevel() {
        return globalRiskLevel != null ? globalRiskLevel : BoundingBox.RiskLevel.MEDIUM;
    }

    public void setGlobalRiskLevel(BoundingBox.RiskLevel riskLevel) {
        this.globalRiskLevel = riskLevel != null ? riskLevel : BoundingBox.RiskLevel.MEDIUM;
    }

    public void setUIResources(int statusIndicatorDrawable, int functionIconResId,
                              int functionIconTint, int backgroundResId, int statusIconTint) {
        this.statusIndicatorDrawable = statusIndicatorDrawable;
        this.functionIconResId = functionIconResId;
        this.functionIconTint = functionIconTint;
        this.backgroundResId = backgroundResId;
        this.statusIconTint = statusIconTint;
    }

    // Kept for backward compatibility — no longer tracks active state
    public void setShowBounds(boolean show) {}
    public boolean isShowBounds() { return false; }

    public BoundingBox getBoundingBox() { return getFirstCoordinate(); }

    public void addCoordinate(BoundingBox coordinate) {
        if (coordinates == null) coordinates = new ArrayList<>();
        coordinates.add(coordinate);
    }

    public BoundingBox getFirstCoordinate() {
        return (coordinates != null && !coordinates.isEmpty()) ? coordinates.get(0) : null;
    }

    public void setFirstCoordinateBounds(int left, int top, int right, int bottom, BoundingBox.RiskLevel riskLevel) {
        BoundingBox firstBox = getFirstCoordinate();
        if (firstBox != null) {
            firstBox.setLeft(left);
            firstBox.setTop(top);
            firstBox.setRight(right);
            firstBox.setBottom(bottom);
            firstBox.setRiskLevel(riskLevel);
        }
    }

    public void createAndSetFirstCoordinate(int left, int top, int right, int bottom, BoundingBox.RiskLevel riskLevel) {
        if (coordinates == null) coordinates = new ArrayList<>();
        coordinates.clear();
        coordinates.add(new BoundingBox(left, top, right, bottom, riskLevel));
    }

    // ========== Static resource mapping ==========

    public static int getIconResourceIdFromIconName(String iconName) {
        if (iconName == null) return R.drawable.ic_profile;
        switch (iconName.toLowerCase()) {
            case "ic_camera":       return R.drawable.ic_camera;
            case "ic_contacts":     return R.drawable.ic_contacts;
            case "ic_email":        return R.drawable.ic_email;
            case "ic_location":     return R.drawable.ic_location;
            case "ic_microphone":   return R.drawable.ic_microphone;
            case "ic_name":         return R.drawable.ic_name;
            case "ic_birthday":     return R.drawable.ic_birthday;
            case "ic_address":      return R.drawable.ic_address;
            case "ic_phone":        return R.drawable.ic_phone;
            case "ic_financial":    return R.drawable.ic_financial;
            case "ic_profile":      return R.drawable.ic_profile;
            case "ic_social_media": return R.drawable.ic_social_media;
            default:                return R.drawable.ic_other;
        }
    }

    public static int getRiskLevelBackgroundResourceId(BoundingBox.RiskLevel riskLevel) {
        switch (riskLevel) {
            case HIGH:   return R.drawable.function_item_background_red;
            case MEDIUM: return R.drawable.function_item_background_orange;
            case LOW:    return R.drawable.function_item_background_green;
            default:     return R.drawable.function_item_background_orange;
        }
    }

    public static int getRiskLevelFunctionIconColorResourceId(BoundingBox.RiskLevel riskLevel) {
        switch (riskLevel) {
            case HIGH:   return R.color.function_icon_high;
            case MEDIUM: return R.color.function_icon_medium;
            case LOW:    return R.color.function_icon_low;
            default:     return R.color.function_icon_medium;
        }
    }

    public static int getRiskLevelStatusIconColorResourceId(BoundingBox.RiskLevel riskLevel) {
        switch (riskLevel) {
            case HIGH:   return R.color.status_icon_high;
            case MEDIUM: return R.color.status_icon_medium;
            case LOW:    return R.color.status_icon_low;
            default:     return R.color.status_icon_medium;
        }
    }

    public static int getRiskLevelColorResourceId(BoundingBox.RiskLevel riskLevel) {
        switch (riskLevel) {
            case HIGH:   return R.color.risk_high;
            case MEDIUM: return R.color.risk_medium;
            case LOW:    return R.color.risk_low;
            default:     return R.color.risk_medium;
        }
    }

    public static BoundingBox.RiskLevel fromEnglishName(String name) {
        if (name == null) return BoundingBox.RiskLevel.MEDIUM;
        switch (name.toLowerCase()) {
            case "high":   return BoundingBox.RiskLevel.HIGH;
            case "medium": return BoundingBox.RiskLevel.MEDIUM;
            case "low":    return BoundingBox.RiskLevel.LOW;
            default:       return BoundingBox.RiskLevel.MEDIUM;
        }
    }
}
