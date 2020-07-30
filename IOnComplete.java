package org.sombrenuit.dk.kettleboy;

public interface IOnComplete<T>
{
    void onComplete(T val);
    void onError();
}
