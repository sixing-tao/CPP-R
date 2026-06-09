package com.example.cppr;

import android.os.Parcel;
import android.os.Parcelable;

public class BoundingBox implements Parcelable {
    private int left;
    private int top;
    private int right;
    private int bottom;
    private RiskLevel riskLevel;

    public enum RiskLevel {
        HIGH,   // red
        MEDIUM, // orange
        LOW     // green
    }

    public BoundingBox(int left, int top, int right, int bottom, RiskLevel riskLevel) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.riskLevel = riskLevel;
    }

    public int getLeft() { return left; }
    public int getTop() { return top; }
    public int getRight() { return right; }
    public int getBottom() { return bottom; }
    public RiskLevel getRiskLevel() { return riskLevel; }

    public void setLeft(int left) { this.left = left; }
    public void setTop(int top) { this.top = top; }
    public void setRight(int right) { this.right = right; }
    public void setBottom(int bottom) { this.bottom = bottom; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public int getWidth() { return right - left; }
    public int getHeight() { return bottom - top; }

    protected BoundingBox(Parcel in) {
        left = in.readInt();
        top = in.readInt();
        right = in.readInt();
        bottom = in.readInt();
        riskLevel = RiskLevel.valueOf(in.readString());
    }

    public static final Creator<BoundingBox> CREATOR = new Creator<BoundingBox>() {
        @Override
        public BoundingBox createFromParcel(Parcel in) {
            return new BoundingBox(in);
        }

        @Override
        public BoundingBox[] newArray(int size) {
            return new BoundingBox[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(left);
        dest.writeInt(top);
        dest.writeInt(right);
        dest.writeInt(bottom);
        dest.writeString(riskLevel.name());
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
