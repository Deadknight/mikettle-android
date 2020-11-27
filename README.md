# mikettle-android

Mi kettle (mikettle) android library

Based on https://github.com/aprosvetova/xiaomi-kettle and https://github.com/drndos/mikettle

# usage


```java
//Second param is mac
//Third param is product id, it maybe 130
miKettle = new MiKettle(MainActivity.this, "00:00:00:00:00:00", (byte) 275);
try
{
    miKettle.subscribe(new IOnData<byte[]>()
    {
        @Override
        public void onData(byte[] val)
        {
            final HashMap<String, String> vals = miKettle.parse_data(val);
            MainActivity.this.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    mTvTemp.setText(vals.get(MiKettle.MI_CURRENT_TEMPERATURE));
                }
            });
        }
    });
}
catch (Exception e)
{
    e.printStackTrace();
}
```
